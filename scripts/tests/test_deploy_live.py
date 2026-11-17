import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock, call

SPEC = importlib.util.spec_from_file_location('deploy_live', Path(__file__).resolve().parents[1] / 'deploy_live.py')
deploy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(deploy)


class LocalSftp:
    """SFTP protocol fixture backed by a disposable directory; no network or credentials."""
    def __init__(self, root):
        self.root = root
        self.fail_new_plugins_once = False
        self.corrupt_upload = False
    def path(self, remote):
        return self.root / remote.lstrip('/')
    def lstat(self, path):
        return self.path(path).lstat()
    def mkdir(self, path, mode=0o700):
        self.path(path).mkdir(mode=mode)
    def open(self, path, mode):
        return self.path(path).open(mode)
    def chmod(self, path, mode):
        self.path(path).chmod(mode)
    def listdir(self, path):
        return os.listdir(self.path(path))
    def rename(self, source, target):
        if self.fail_new_plugins_once and '/new/' in source and source.endswith('/plugins'):
            self.fail_new_plugins_once = False
            raise OSError('Injected installation failure')
        if self.path(target).exists():
            raise FileExistsError(target)
        self.path(source).rename(self.path(target))
    def put(self, source, target, confirm=True):
        shutil.copy2(source, self.path(target))
        if self.corrupt_upload:
            self.path(target).write_bytes(b'corrupt')


class DeployTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.sftp = LocalSftp(self.root)
        self.release = '/' + deploy.CONTROL + '/releases/test'
        self.source = self.release + '/new'
        deploy.mkdirs(self.sftp, self.source)
    def tearDown(self):
        self.directory.cleanup()
    def write(self, path, text):
        target = self.sftp.path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    def read(self, path):
        return self.sftp.path(path).read_text()

    def test_recover_interrupted_staging_lock_preserves_all_remote_data(self):
        control = '/' + deploy.CONTROL
        self.write(control + '/lock/owner.json', json.dumps({'release': 'test'}))
        self.write(self.source + '/partial.upload', 'partial')
        deploy.recover_staging_lock(self.sftp, control, 'test')
        self.assertFalse(self.sftp.path(control + '/lock').exists())
        archived = list(self.sftp.path(control + '/abandoned-locks').iterdir())
        self.assertEqual(1, len(archived))
        self.assertEqual({'release': 'test'}, json.loads((archived[0] / 'owner.json').read_text()))
        self.assertEqual('partial', self.read(self.source + '/partial.upload'))

    def test_recover_refuses_a_different_lock_owner(self):
        control = '/' + deploy.CONTROL
        self.write(control + '/lock/owner.json', json.dumps({'release': 'other'}))
        with self.assertRaises(deploy.DeployError):
            deploy.recover_staging_lock(self.sftp, control, 'test')
        self.assertTrue(self.sftp.path(control + '/lock').exists())

    def test_recover_refuses_any_activation_evidence(self):
        control = '/' + deploy.CONTROL
        self.write(control + '/lock/owner.json', json.dumps({'release': 'test'}))
        for phase in ('activating', 'active', 'reverted'):
            self.write(self.release + '/manifest.json', json.dumps({'phase': phase}))
            with self.assertRaises(deploy.DeployError):
                deploy.recover_staging_lock(self.sftp, control, 'test')
        self.write(self.release + '/manifest.json', json.dumps({'phase': 'staging'}))
        deploy.mkdirs(self.sftp, self.release + '/previous')
        with self.assertRaises(deploy.DeployError):
            deploy.recover_staging_lock(self.sftp, control, 'test')

    def test_plugin_update_preserves_live_data_and_backs_up_old_jar(self):
        self.write('/plugins/MinecraftChallenges.jar', 'old')
        self.write('/plugins/MinecraftChallenges/config.yml', 'live config')
        self.write('/world/region/data.mca', 'live world')
        self.write(self.source + '/plugins/MinecraftChallenges.jar', 'new')
        manifest = {'mode': 'plugin'}
        deploy.activate(self.sftp, '/', self.source, self.release, 'plugin', manifest)
        self.assertEqual('new', self.read('/plugins/MinecraftChallenges.jar'))
        self.assertEqual('live config', self.read('/plugins/MinecraftChallenges/config.yml'))
        self.assertEqual('live world', self.read('/world/region/data.mca'))
        self.assertEqual('old', self.read(self.release + '/previous/plugins/MinecraftChallenges.jar'))
        self.assertEqual('active', manifest['phase'])

    def test_full_replacement_removes_old_active_files_but_keeps_complete_backup(self):
        self.write('/plugins/Old.jar', 'old plugin')
        self.write('/world/region/data.mca', 'old world')
        self.write('/old-only.txt', 'obsolete')
        self.write('/.hidden-live-data', 'hidden')
        self.write(self.source + '/plugins/MinecraftChallenges.jar', 'new plugin')
        self.write(self.source + '/world/region/data.mca', 'local world')
        manifest = {'mode': 'replace'}
        deploy.activate(self.sftp, '/', self.source, self.release, 'replace', manifest)
        self.assertEqual('local world', self.read('/world/region/data.mca'))
        self.assertFalse(self.sftp.path('/plugins/Old.jar').exists())
        self.assertFalse(self.sftp.path('/old-only.txt').exists())
        self.assertEqual('old world', self.read(self.release + '/previous/world/region/data.mca'))
        self.assertEqual('hidden', self.read(self.release + '/previous/.hidden-live-data'))
        self.assertTrue(self.sftp.path('/' + deploy.CONTROL).is_dir())

    def test_failure_during_full_install_restores_all_old_entries(self):
        self.write('/a.txt', 'old')
        self.write('/plugins/Old.jar', 'old plugin')
        self.write(self.source + '/a.txt', 'new')
        self.write(self.source + '/plugins/New.jar', 'new plugin')
        self.sftp.fail_new_plugins_once = True
        manifest = {}
        with self.assertRaises(deploy.DeployError):
            deploy.activate(self.sftp, '/', self.source, self.release, 'replace', manifest)
        self.assertEqual('old', self.read('/a.txt'))
        self.assertEqual('old plugin', self.read('/plugins/Old.jar'))
        self.assertFalse(self.sftp.path('/plugins/New.jar').exists())
        self.assertEqual('new', self.read(self.release + '/failed/a.txt'))
        self.assertEqual('reverted', manifest['phase'])

    def test_first_plugin_install_and_rollback_to_no_plugin(self):
        self.write(self.source + '/plugins/MinecraftChallenges.jar', 'first install')
        deploy.activate(self.sftp, '/', self.source, self.release, 'plugin', {})
        rollback = '/' + deploy.CONTROL + '/releases/rollback'
        deploy.mkdirs(self.sftp, rollback)
        deploy.activate(self.sftp, '/', self.release + '/previous', rollback, 'plugin', {})
        self.assertFalse(self.sftp.path('/plugins/MinecraftChallenges.jar').exists())
        self.assertEqual('first install', self.read(rollback + '/previous/plugins/MinecraftChallenges.jar'))

    def test_full_rollback_preserves_current_state_as_another_backup(self):
        self.write('/world/region.mca', 'old live')
        self.write(self.source + '/world/region.mca', 'new live')
        deploy.activate(self.sftp, '/', self.source, self.release, 'replace', {})
        self.write('/world/region.mca', 'new progress')
        rollback = '/' + deploy.CONTROL + '/releases/rollback'
        deploy.mkdirs(self.sftp, rollback)
        deploy.activate(self.sftp, '/', self.release + '/previous', rollback, 'replace', {})
        self.assertEqual('old live', self.read('/world/region.mca'))
        self.assertEqual('new progress', self.read(rollback + '/previous/world/region.mca'))

    def test_upload_checksum_failure_never_activates_file(self):
        source = self.root / 'local.jar'
        source.write_bytes(b'plugin')
        with source.open('rb') as stream:
            expected = {'sha256': deploy.digest(stream)}
        self.sftp.corrupt_upload = True
        with self.assertRaises(deploy.DeployError):
            deploy.upload_verified(self.sftp, source, self.source + '/plugin.jar', expected)
        self.assertFalse(self.sftp.path(self.source + '/plugin.jar').exists())

    def test_plugin_destination_cannot_follow_a_symlink(self):
        other = self.root / 'outside-plugins'
        other.mkdir()
        (self.root / 'plugins').symlink_to(other)
        self.write(self.source + '/plugins/MinecraftChallenges.jar', 'new')
        with self.assertRaises(deploy.DeployError):
            deploy.activate(self.sftp, '/', self.source, self.release, 'plugin', {})
        self.assertEqual([], list(other.iterdir()))

    def test_live_properties_preserve_allocated_network_and_local_gameplay(self):
        merged = deploy.live_properties('server-port=25565\nserver-ip=127.0.0.1\nview-distance=8\nonline-mode=true\nrcon.password=local-secret\n',
                                        'server-port=26200\nserver-ip=0.0.0.0\nrcon.password=live-secret\n')
        values = deploy.properties(merged)
        self.assertEqual('26200', values['server-port'])
        self.assertEqual('0.0.0.0', values['server-ip'])
        self.assertEqual('8', values['view-distance'])
        self.assertEqual('true', values['online-mode'])
        self.assertEqual('live-secret', values['rcon.password'])
        self.assertNotIn('local-secret', merged)
        self.assertEqual('false', values['management-server-enabled'])

    def test_missing_port_blocks_first_full_activation(self):
        with self.assertRaises(deploy.DeployError):
            deploy.live_properties('server-port=25565', '')
        self.assertEqual('27000', deploy.properties(deploy.live_properties('motd=Local', '', 27000))['server-port'])

    def test_full_inventory_renames_paper_and_excludes_runtime_only(self):
        server = self.root / 'local-server'
        for relative, text in {'paper-26.3-222.jar': 'paper', 'plugins/MinecraftChallenges.jar': 'plugin',
                               'eula.txt': 'eula=true', 'server.properties': 'motd=local',
                               'world/dimensions/minecraft/smp/region/a.mca': 'world', 'world/session.lock': 'lock',
                               'plugins/MinecraftChallenges/smp/players/user.yml': 'inventory',
                               'plugins/.paper-remapped/cache.jar': 'cache', 'logs/latest.log': 'log',
                               'libraries/cache.jar': 'lib', 'backups/smp/backup.zip': 'backup'}.items():
            path = server / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        files = deploy.local_inventory(server, 'server.jar')
        self.assertIn('server.jar', files)
        self.assertIn('world/dimensions/minecraft/smp/region/a.mca', files)
        self.assertIn('plugins/MinecraftChallenges/smp/players/user.yml', files)
        self.assertIn('backups/smp/backup.zip', files)
        for excluded in ('logs/latest.log', 'world/session.lock', 'libraries/cache.jar', 'plugins/.paper-remapped/cache.jar'):
            self.assertNotIn(excluded, files)

    def test_java_style_world_lock_blocks_local_snapshot(self):
        server = self.root / 'server'
        server.mkdir()
        lock = server / 'session.lock'
        lock.write_text('lock')
        code = 'import fcntl,sys; f=open(sys.argv[1],"r+b"); fcntl.lockf(f,fcntl.LOCK_EX); print("locked",flush=True); sys.stdin.read()'
        child = subprocess.Popen([sys.executable, '-c', code, str(lock)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual('locked', child.stdout.readline().strip())
            with self.assertRaises(deploy.DeployError):
                deploy.ensure_local_stopped(server)
        finally:
            child.communicate(input='', timeout=5)
        deploy.ensure_local_stopped(server)

    def test_paths_cannot_escape_or_overwrite_control_directory(self):
        for path in ('../world', '/world', 'plugins/../../secret', 'a\nb', 'plugins\\bad'):
            with self.assertRaises(deploy.DeployError):
                deploy.relative_path(path)
        self.write(self.source + '/' + deploy.CONTROL + '/bad', 'bad')
        with self.assertRaises(deploy.DeployError):
            deploy.activate(self.sftp, '/', self.source, self.release, 'replace', {})

    def test_noninteractive_replacement_requires_both_explicit_confirmations(self):
        config = {'name': 'kinetic-live'}
        class Args:
            live_stopped = True
            confirm_replace = None
        with patch('sys.stdin', io.StringIO()):
            with self.assertRaises(deploy.DeployError):
                deploy.require_stopped(config, 'replace', Args())
            Args.confirm_replace = 'kinetic-live'
            deploy.require_stopped(config, 'replace', Args())


class PanelTests(unittest.TestCase):
    def panel(self, timeout=180):
        return deploy.PanelClient({'url': 'https://kineticpanel.net', 'server_id': '18599ff1'}, 'ptlc_TEST_ONLY', timeout)

    def test_stop_waits_for_offline_and_never_uses_kill(self):
        panel = self.panel()
        panel.call = Mock(side_effect=[{'attributes': {'current_state': 'running'}}, {},
                                      {'attributes': {'current_state': 'stopping'}},
                                      {'attributes': {'current_state': 'offline'}},
                                      {'attributes': {'current_state': 'offline'}}])
        with patch.object(deploy.time, 'sleep'):
            panel.stop()
        self.assertIn(call('/power', {'signal': 'stop'}), panel.call.call_args_list)
        self.assertNotIn(call('/power', {'signal': 'kill'}), panel.call.call_args_list)
        self.assertEqual(5, panel.call.call_count)

    def test_offline_server_is_not_stopped_again(self):
        panel = self.panel()
        panel.call = Mock(return_value={'attributes': {'current_state': 'offline'}})
        panel.stop()
        self.assertTrue(all(args == call('/resources') for args in panel.call.call_args_list))

    def test_start_waits_for_running(self):
        panel = self.panel()
        panel.call = Mock(side_effect=[{}, {'attributes': {'current_state': 'starting'}},
                                      {'attributes': {'current_state': 'running'}}])
        with patch.object(deploy.time, 'sleep'):
            panel.start()
        self.assertEqual(call('/power', {'signal': 'start'}), panel.call.call_args_list[0])

    def test_unknown_state_and_stop_timeout_block_activation(self):
        panel = self.panel(timeout=0)
        panel.call = Mock(return_value={'attributes': {'current_state': 'starting'}})
        with self.assertRaises(deploy.DeployError):
            panel.stop()
        for payload in ({'attributes': {'current_state': 'unexpected'}}, [], {'attributes': None}):
            panel.call = Mock(return_value=payload)
            with self.assertRaises(deploy.DeployError):
                panel.assert_offline()

    def test_https_and_client_tokens_are_required(self):
        for url in ('http://kineticpanel.net', 'https://name:secret@kineticpanel.net', 'https://kineticpanel.net?token=x'):
            with self.assertRaises(deploy.DeployError):
                deploy.PanelClient({'url': url, 'server_id': '18599ff1'}, 'ptlc_TEST_ONLY')
        with self.assertRaises(deploy.DeployError):
            deploy.PanelClient({'url': 'https://kineticpanel.net', 'server_id': '../other'}, 'ptlc_TEST_ONLY')
        with self.assertRaises(deploy.DeployError):
            deploy.PanelClient({'url': 'https://kineticpanel.net', 'server_id': '18599ff1'}, 'wrong_token')

    def test_documented_auth_headers_and_endpoint(self):
        panel = self.panel()
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'{"attributes":{"current_state":"offline"}}'
        panel.opener.open = Mock(return_value=response)
        self.assertEqual('offline', panel.state())
        req = panel.opener.open.call_args.args[0]
        self.assertEqual('https://kineticpanel.net/api/client/servers/18599ff1/resources', req.full_url)
        self.assertEqual('Bearer ptlc_TEST_ONLY', req.get_header('Authorization'))
        redirect = next(h for h in panel.opener.handlers if isinstance(h, deploy.request.HTTPRedirectHandler))
        self.assertIsNone(redirect.redirect_request(req, None, 302, '', {}, 'https://elsewhere.invalid'))


if __name__ == '__main__':
    unittest.main()
