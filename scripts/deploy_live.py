#!/usr/bin/env python3
"""Stage verified files over SFTP, then replace only while the operator has stopped Paper."""
import argparse
from contextlib import suppress
import base64
import errno
import fcntl
import getpass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import stat
import subprocess
import sys
import time
from urllib import request, error as http_error, parse
from datetime import datetime, timezone
from uuid import uuid4

PROJECT = Path(__file__).resolve().parent.parent
CONTROL = '.minecraftchallenges-deploy'
PLUGIN = 'plugins/MinecraftChallenges.jar'
ROOT_EXCLUDES = {'logs', 'crash-reports', 'cache', 'libraries', 'versions', CONTROL,
                 '.console.fifo', '.dev-server.pid', '.dev-server.wrapper.pid'}
NETWORK_KEYS = {'server-ip', 'server-port', 'query.port', 'rcon.port', 'rcon.password',
                'enable-rcon', 'enable-query', 'management-server-enabled', 'management-server-host',
                'management-server-port', 'management-server-secret', 'management-server-tls-enabled',
                'management-server-tls-keystore', 'management-server-tls-keystore-password'}


class DeployError(Exception):
    pass


def relative_path(value):
    path = PurePosixPath(value)
    if not value or path.is_absolute() or any(p in ('..', '.') for p in value.split('/')) or '\\' in value:
        raise DeployError('Unsicherer relativer Pfad: ' + value)
    if any(ord(c) < 32 for c in value):
        raise DeployError('Steuerzeichen im Dateinamen sind nicht erlaubt.')
    return str(path)


def remote_join(root, relative):
    return posixpath.join(root, relative_path(relative))


def exists(sftp, path):
    try:
        return sftp.lstat(path)
    except OSError as error:
        if error.errno == errno.ENOENT:
            return None
        raise


def mkdirs(sftp, path):
    parent = posixpath.dirname(path)
    info = exists(sftp, path)
    if info is not None:
        if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode):
            raise DeployError('Kein sicherer Zielordner: ' + path)
        return
    if parent and parent != path:
        mkdirs(sftp, parent)
    sftp.mkdir(path, mode=0o700)


def read_remote(sftp, path, missing=None):
    info = exists(sftp, path)
    if info is None:
        return missing
    if not stat.S_ISREG(info.st_mode) or info.st_size > 1024 * 1024:
        raise DeployError('Ungültige Konfigurationsdatei: ' + path)
    with sftp.open(path, 'rb') as stream:
        return stream.read().decode('utf-8')


def write_remote(sftp, path, text):
    with sftp.open(path, 'wb') as stream:
        stream.write(text.encode('utf-8'))
    sftp.chmod(path, 0o600)


def digest(stream):
    result = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        result.update(block)
    return result.hexdigest()


def properties(text):
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith(('#', '!')) and '=' in line:
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip()
    return result


def live_properties(local_text, remote_text, configured_port=None):
    """Keep all gameplay settings; the allocated live networking belongs to the host."""
    remote = properties(remote_text or '')
    port = configured_port if configured_port is not None else remote.get('server-port')
    try:
        if not 1 <= int(port) <= 65535:
            raise ValueError()
    except (ValueError, TypeError):
        raise DeployError('Live-Spielport fehlt. minecraft_port in deploy/live.json aus dem Panel eintragen.')
    preserved = {key: value for key, value in remote.items() if key in NETWORK_KEYS}
    preserved['server-port'] = str(port)
    preserved['server-ip'] = remote.get('server-ip', '')
    # Do not expose a local development management service or its credentials on first deployment.
    preserved.setdefault('enable-rcon', 'false')
    preserved.setdefault('enable-query', 'false')
    preserved.setdefault('management-server-enabled', 'false')
    output = [line for line in local_text.splitlines()
              if line.split('=', 1)[0].strip() not in NETWORK_KEYS]
    output.extend(key + '=' + value for key, value in sorted(preserved.items()))
    return '\n'.join(output) + '\n'


def local_inventory(server, server_jar):
    jars = sorted(server.glob('paper-*.jar'))
    if len(jars) != 1:
        raise DeployError('Für einen Komplett-Upload muss genau eine paper-*.jar in server/ liegen.')
    files = {}
    for directory, directories, names in os.walk(server, followlinks=False):
        parent = Path(directory)
        for name in list(directories):
            relative = (parent / name).relative_to(server)
            if relative.parts[0] in ROOT_EXCLUDES or relative.parts == ('plugins', '.paper-remapped'):
                directories.remove(name)
            elif (parent / name).is_symlink():
                raise DeployError('Symlink im lokalen Server: ' + str(relative))
        for name in names:
            path = parent / name
            relative = path.relative_to(server)
            if relative.parts[0] in ROOT_EXCLUDES or name in {'session.lock', '.DS_Store'}:
                continue
            if not path.is_file() or path.is_symlink():
                raise DeployError('Keine reguläre Datei: ' + str(relative))
            target = server_jar if path == jars[0] else relative.as_posix()
            relative_path(target)
            if target in files:
                raise DeployError('Dateikollision beim Upload: ' + target)
            files[target] = path
    for required in ('server.properties', 'eula.txt', server_jar, PLUGIN):
        if required not in files:
            raise DeployError('Lokale Serverdatei fehlt: ' + required)
    if properties(files['eula.txt'].read_text()).get('eula') != 'true':
        raise DeployError('Die lokale EULA ist noch nicht akzeptiert.')
    return files


def ensure_local_stopped(server):
    # Java's FileChannel locks are POSIX record locks, not BSD flock locks.
    for path in server.rglob('session.lock'):
        if path.is_symlink():
            raise DeployError('Symlink als Welt-Lock: ' + str(path))
        with path.open('r+b') as stream:
            try:
                fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.lockf(stream, fcntl.LOCK_UN)
            except OSError:
                raise DeployError('Lokaler Server läuft noch: ' + str(path))


def snapshot(files, directory):
    result = {}
    for relative, source in sorted(files.items()):
        destination = directory / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        before = source.stat()
        shutil.copy2(source, destination)
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise DeployError('Lokale Datei wurde während des Snapshots verändert: ' + relative)
        with destination.open('rb') as stream:
            result[relative] = {'sha256': digest(stream), 'bytes': destination.stat().st_size}
    return result


def upload_verified(sftp, source, destination, expected):
    mkdirs(sftp, posixpath.dirname(destination))
    temporary = destination + '.uploading'
    sftp.put(str(source), temporary, confirm=True)
    with sftp.open(temporary, 'rb') as stream:
        # Paramiko's default unbuffered reads add one network round-trip per block.
        # Bound prefetch keeps verification fast without loading the complete file into RAM.
        if hasattr(stream, 'prefetch'):
            stream.prefetch(file_size=expected['bytes'], max_concurrent_requests=16)
        actual = digest(stream)
    if actual != expected['sha256']:
        raise DeployError('Prüfsumme stimmt nicht: ' + destination)
    sftp.rename(temporary, destination)


def names(sftp, root):
    result = sorted(sftp.listdir(root))
    for name in result:
        if '/' in relative_path(name):
            raise DeployError('Ungültiger Eintrag im Serverordner.')
    return result


def recover_staging_lock(sftp, control, release_id):
    """Explicit recovery only for an interrupted upload, never an interrupted activation."""
    if '/' in relative_path(release_id):
        raise DeployError('Ungültige Release-ID für Lock-Wiederherstellung.')
    lock = remote_join(control, 'lock')
    owner_path = remote_join(lock, 'owner.json')
    owner_text = read_remote(sftp, owner_path)
    if owner_text is None or json.loads(owner_text).get('release') != release_id:
        raise DeployError('Lock gehört nicht zum angegebenen abgebrochenen Release.')
    if names(sftp, lock) != ['owner.json']:
        raise DeployError('Lock enthält unerwartete Dateien; manuelle Prüfung erforderlich.')
    release = remote_join(control, 'releases/' + release_id)
    manifest = json.loads(read_remote(sftp, remote_join(release, 'manifest.json')) or '{}')
    if manifest and manifest.get('phase') != 'staging':
        raise DeployError('Release hat die Upload-Phase bereits verlassen; Live-Stand zuerst prüfen.')
    if any(exists(sftp, remote_join(release, folder)) for folder in ('previous', 'failed')):
        raise DeployError('Aktivierung könnte begonnen haben; Sicherung zuerst prüfen.')
    archive = remote_join(control, 'abandoned-locks')
    mkdirs(sftp, archive)
    if read_remote(sftp, owner_path) != owner_text:
        raise DeployError('Deployment-Lock wurde während der Prüfung verändert.')
    destination = remote_join(archive, release_id + '-' + uuid4().hex[:8])
    sftp.rename(lock, destination)
    print('Verwaisten Upload-Lock gesichert:', destination, flush=True)


def activate(sftp, root, source, release, mode, manifest):
    """Move old entries to a recoverable backup. Never recursively delete remote data."""
    previous = remote_join(release, 'previous')
    failed = remote_join(release, 'failed')
    mkdirs(sftp, previous)
    mkdirs(sftp, failed)
    old = [PLUGIN] if mode == 'plugin' and exists(sftp, remote_join(root, PLUGIN)) else []
    new = [PLUGIN] if mode == 'plugin' and exists(sftp, remote_join(source, PLUGIN)) else []
    if mode == 'replace':
        old = [name for name in names(sftp, root) if name != CONTROL]
        new = names(sftp, source)
        if CONTROL in new:
            raise DeployError('Deployment-Verzeichnis darf nicht mit hochgeladen werden.')
    # Check all destination parent directories before the first mutation.
    for relative in old + new:
        mkdirs(sftp, posixpath.dirname(remote_join(root, relative)))
    manifest.update(phase='activating', before=old, after=new)
    manifest_path = remote_join(release, 'manifest.json')
    write_remote(sftp, manifest_path, json.dumps(manifest, indent=2))
    moved, installed = [], []
    try:
        for relative in old:
            backup = remote_join(previous, relative)
            mkdirs(sftp, posixpath.dirname(backup))
            sftp.rename(remote_join(root, relative), backup)
            moved.append(relative)
        for relative in new:
            sftp.rename(remote_join(source, relative), remote_join(root, relative))
            installed.append(relative)
        manifest['phase'] = 'active'
        write_remote(sftp, manifest_path, json.dumps(manifest, indent=2))
    except BaseException as error:
        try:
            for relative in reversed(installed):
                destination = remote_join(failed, relative)
                mkdirs(sftp, posixpath.dirname(destination))
                sftp.rename(remote_join(root, relative), destination)
            for relative in reversed(moved):
                sftp.rename(remote_join(previous, relative), remote_join(root, relative))
            manifest['phase'] = 'reverted'
            write_remote(sftp, manifest_path, json.dumps(manifest, indent=2))
        except BaseException:
            raise DeployError('Verbindung/Austausch fehlgeschlagen. Live NICHT starten. Sicherung und Manifest: ' + release) from error
        raise DeployError('Austausch fehlgeschlagen; vorheriger Live-Stand wurde wiederhergestellt.') from error


def load_config(path):
    try:
        config = json.loads(path.read_text())
    except FileNotFoundError:
        raise DeployError('Konfiguration fehlt. Zuerst ./scripts/setup-deploy.sh ausführen.')
    for key in ('name', 'host', 'username', 'remote_root', 'server_jar'):
        if not isinstance(config.get(key), str) or not config[key].strip():
            raise DeployError('Konfigurationswert fehlt: ' + key)
    if config['host'].startswith('sftp://'):
        config['host'] = config['host'][7:].rstrip('/')
    if '/' in config['host'] or ':' in config['host'] or 'YOUR-' in config['host']:
        raise DeployError('Bitte den echten SFTP-Host ohne Protokoll/Port eintragen.')
    if not 1 <= int(config.get('port', 2022)) <= 65535:
        raise DeployError('Ungültiger SFTP-Port.')
    jar = relative_path(config['server_jar'])
    if '/' in jar or not jar.endswith('.jar'):
        raise DeployError('server_jar muss der JAR-Dateiname aus Kinetics Startkonfiguration sein.')
    if not config['remote_root'].startswith('/') or '..' in PurePosixPath(config['remote_root']).parts:
        raise DeployError('remote_root muss ein absoluter SFTP-Pfad sein.')
    return config


def connect(config):
    try:
        import paramiko
    except ImportError:
        raise DeployError('SFTP-Abhängigkeit fehlt. Bitte ./scripts/setup-deploy.sh ausführen.')
    known_hosts = PROJECT / 'deploy/known_hosts'
    class VerifyHost(paramiko.MissingHostKeyPolicy):
        def missing_host_key(self, client, hostname, key):
            fingerprint = 'SHA256:' + base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')
            print('Neuer SFTP-Host:', hostname, '\nHost-Schlüssel:', fingerprint)
            if input('Fingerprint prüfen. Zum Speichern VERTRAUEN eingeben: ').strip() != 'VERTRAUEN':
                raise DeployError('Host-Schlüssel nicht bestätigt.')
            client.get_host_keys().add(hostname, key.get_name(), key)
            client.save_host_keys(str(known_hosts))
            known_hosts.chmod(0o600)
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    if known_hosts.exists():
        client.load_host_keys(str(known_hosts))
    client.set_missing_host_key_policy(VerifyHost())
    key = config.get('private_key')
    password = None if key else os.environ.get('KINETIC_SFTP_PASSWORD')
    if not key and password is None:
        password = getpass.getpass('Kinetic SFTP-Passwort (wird nicht gespeichert): ')
    try:
        client.connect(config['host'], port=int(config.get('port', 2022)), username=config['username'],
                       password=password, key_filename=os.path.expanduser(key) if key else None,
                       allow_agent=bool(key), look_for_keys=bool(key), timeout=30, auth_timeout=30, banner_timeout=30)
        client.get_transport().set_keepalive(20)
        sftp = client.open_sftp()
        sftp.get_channel().settimeout(60)
        return client, sftp
    except BaseException as error:
        client.close()
        if isinstance(error, paramiko.SSHException):
            raise DeployError('SFTP-Verbindung fehlgeschlagen: ' + str(error)) from None
        raise


def require_stopped(config, mode, args):
    phrase = ('ERSETZEN ' if mode == 'replace' else 'GESTOPPT ') + config['name']
    print('\nJetzt den LIVE-Server im Kinetic-Panel sauber stoppen und auf OFFLINE warten.')
    if mode == 'replace':
        print('Alle Live-Welten, Spielerdaten und Plugins werden durch den gewählten Stand ersetzt.')
    if args.live_stopped and (mode != 'replace' or args.confirm_replace == config['name']):
        return
    if not sys.stdin.isatty():
        raise DeployError('Stop im Panel erforderlich. Danach --live-stopped verwenden; für Komplett-Ersatz zusätzlich --confirm-replace ' + config['name'])
    if input('Erst wenn offline, exakt "' + phrase + '" eingeben: ').strip() != phrase:
        raise DeployError('Abgebrochen. Aktive Live-Dateien wurden nicht verändert.')


class PanelClient:
    """Documented Kinetic Client API; no cookie scraping or undocumented remote endpoints."""
    def __init__(self, config, token, timeout=180):
        parsed = parse.urlsplit(config.get('url', ''))
        if parsed.scheme != 'https' or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise DeployError('Die Panel-API benötigt eine HTTPS-Basisadresse ohne Zugangsdaten.')
        self.base = config['url'].rstrip('/')
        self.server = config.get('server_id', '')
        if not re.fullmatch(r'[a-zA-Z0-9_-]+', self.server) or self.server.startswith('YOUR-'):
            raise DeployError('panel.server_id in deploy/live.json fehlt.')
        if not token.startswith('ptlc_') or any(c.isspace() for c in token):
            raise DeployError('Ein vollständiger Kinetic Client-API-Key (ptlc_...) wird benötigt.')
        self.token = token
        self.timeout = timeout
        class NoRedirect(request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        self.opener = request.build_opener(NoRedirect())

    def call(self, suffix, body=None):
        url = self.base + '/api/client/servers/' + self.server + suffix
        data = None if body is None else json.dumps(body).encode('utf-8')
        req = request.Request(url, data=data, headers={'Authorization': 'Bearer ' + self.token,
                              'Accept': 'application/json', 'Content-Type': 'application/json'})
        try:
            with self.opener.open(req, timeout=30) as response:
                data = response.read()
            return json.loads(data) if data else {}
        except http_error.HTTPError as error:
            # Never print a returned error body: it can include sensitive request context.
            raise DeployError('Kinetic API HTTP ' + str(error.code) + '; Token/Berechtigungen/Panel-Status prüfen.') from None
        except (http_error.URLError, ValueError):
            raise DeployError('Kinetic API nicht erreichbar oder ungültige Antwort. Keine weiteren Live-Dateien ändern.') from None

    def state(self):
        payload = self.call('/resources')
        attributes = payload.get('attributes') if isinstance(payload, dict) else None
        state = attributes.get('current_state') if isinstance(attributes, dict) else None
        if state not in ('offline', 'starting', 'running', 'stopping'):
            raise DeployError('Unbekannter API-Serverstatus. Vorgang sicherheitshalber abgebrochen.')
        return state

    def wait_for(self, wanted):
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            if self.state() == wanted:
                return
            time.sleep(2)
        raise DeployError('Server wurde nicht rechtzeitig ' + wanted + '. Im Panel prüfen; kein harter Kill wird ausgeführt.')

    def stop(self):
        if self.state() != 'offline':
            print('Live-Server wird sauber gestoppt …', flush=True)
            self.call('/power', {'signal': 'stop'})
            self.wait_for('offline')
        self.assert_offline()

    def assert_offline(self):
        if self.state() != 'offline':
            raise DeployError('Live ist nicht offline. Dateiaustausch abgebrochen.')

    def start(self):
        self.call('/power', {'signal': 'start'})
        self.wait_for('running')
        print('Live-Prozess läuft. Minecraft-Start und Plugin-Laden in der Konsole prüfen.')


def new_release_id():
    return datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid4().hex[:8]


def run(args):
    config = load_config(args.config)
    if args.mode == 'rollback' and (not args.release or not re.fullmatch(r'[0-9]{8}T[0-9]{6}Z-[a-f0-9]{8}', args.release)):
        raise DeployError('Rollback benötigt eine Release-ID aus einer früheren Bereitstellung.')
    print('Ziel:', config['name'], config['host'] + ':' + str(config.get('port', 2022)), 'Ordner:', config['remote_root'])
    files = None
    if args.dry_run:
        if args.mode == 'replace':
            files = local_inventory(PROJECT / 'server', config['server_jar'])
        elif args.mode == 'plugin':
            files = {PLUGIN: PROJECT / 'build/libs/MinecraftChallenges.jar'}
        if files is not None:
            for relative, source in sorted(files.items()):
                print('  ', relative)
            total = sum(p.stat().st_size for p in files.values() if p.exists())
            print(len(files), 'Dateien,', round(total / 1024**2, 1), 'MiB. Vorschau: keine Verbindung, kein Stop, kein Upload.')
        else:
            print('Vorschau: Sicherung', args.release, 'wiederherstellen; keine Verbindung/Änderung.')
        return
    panel = None
    if args.auto:
        token = os.environ.get('KINETIC_API_KEY') or getpass.getpass('Kinetic Client-API-Key (wird nicht gespeichert): ')
        panel = PanelClient(config.get('panel', {}), token)
        panel.state()  # Fail before stopping local or uploading if the key/endpoint is invalid.
    release_id = new_release_id()
    local_release = PROJECT / 'build/deploy' / release_id
    if args.mode != 'rollback':
        subprocess.run([str(PROJECT / 'gradlew'), '-p', str(PROJECT), 'build'], check=True)
        if args.mode == 'replace':
            subprocess.run([str(PROJECT / 'scripts/dev-server.sh'), 'stop'], check=True)
            ensure_local_stopped(PROJECT / 'server')
            files = local_inventory(PROJECT / 'server', config['server_jar'])
        else:
            files = {PLUGIN: PROJECT / 'build/libs/MinecraftChallenges.jar'}
        hashes = snapshot(files, local_release / 'files')
        if args.mode == 'replace':
            ensure_local_stopped(PROJECT / 'server')
    else:
        hashes = {}
    client, sftp = connect(config)
    lock = None
    acquired = False
    try:
        root = sftp.normalize(config['remote_root'])
        control = remote_join(root, CONTROL)
        mkdirs(sftp, control)
        lock = remote_join(control, 'lock')
        if exists(sftp, lock) and getattr(args, 'recover_lock', None):
            recover_staging_lock(sftp, control, args.recover_lock)
        if exists(sftp, lock):
            raise DeployError('Ein Deployment-Lock existiert bereits: ' + lock + '. Erst prüfen, ob ein anderer Upload läuft.')
        sftp.mkdir(lock, mode=0o700)  # Atomic: a concurrent deployment cannot acquire the same lock.
        acquired = True
        write_remote(sftp, remote_join(lock, 'owner.json'), json.dumps({'release': release_id}))
        release = remote_join(control, 'releases/' + release_id)
        mkdirs(sftp, release)
        manifest = {'release': release_id, 'mode': args.mode, 'phase': 'staging', 'files': hashes}
        write_remote(sftp, remote_join(release, 'manifest.json'), json.dumps(manifest, indent=2))
        if args.mode == 'rollback':
            original = remote_join(control, 'releases/' + args.release)
            previous_manifest = json.loads(read_remote(sftp, remote_join(original, 'manifest.json')) or '{}')
            mode = previous_manifest.get('mode')
            if previous_manifest.get('phase') != 'active' or mode not in ('plugin', 'replace'):
                raise DeployError('Dieses Release besitzt keine aktive, wiederherstellbare Sicherung.')
            source = remote_join(original, 'previous')
            expected = previous_manifest.get('before', [])
            for entry in expected:
                if exists(sftp, remote_join(source, entry)) is None:
                    raise DeployError('Sicherung unvollständig: ' + entry)
            if mode == 'replace' and set(names(sftp, source)) != set(expected):
                raise DeployError('Sicherung enthält unerwartete Einträge; bitte manuell prüfen.')
            manifest.update(mode=mode, restores=args.release)
        else:
            mode = args.mode
            source = remote_join(release, 'new')
            mkdirs(sftp, source)
            print('Upload in Staging-Ordner:', release_id)
            for index, (relative, expected) in enumerate(hashes.items(), 1):
                upload_verified(sftp, local_release / 'files' / relative, remote_join(source, relative), expected)
                if index == 1 or index % 25 == 0 or index == len(hashes):
                    print('  geprüft:', index, '/', len(hashes), flush=True)
        if panel is None:
            require_stopped(config, mode, args)
        else:
            if mode == 'replace' and args.confirm_replace != config['name']:
                phrase = 'ERSETZEN ' + config['name']
                if not sys.stdin.isatty() or input('Kompletten Live-Stand ersetzen? Exakt "' + phrase + '" eingeben: ').strip() != phrase:
                    raise DeployError('Kompletter Ersatz nicht bestätigt; aktive Live-Dateien unverändert.')
            panel.stop()
        if mode == 'replace' and args.mode != 'rollback':
            local_properties = (local_release / 'files/server.properties').read_text()
            merged = live_properties(local_properties, read_remote(sftp, remote_join(root, 'server.properties'), ''), config.get('minecraft_port'))
            write_remote(sftp, remote_join(source, 'server.properties'), merged)
            if read_remote(sftp, remote_join(source, 'server.properties')) != merged:
                raise DeployError('Live-Netzwerkkonfiguration wurde nicht vollständig übertragen.')
            data = merged.encode('utf-8')
            manifest['files']['server.properties'] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
        if panel is not None:
            panel.assert_offline()
        activate(sftp, root, source, release, mode, manifest)
        if args.mode == 'rollback':
            previous_manifest['phase'] = 'restored'
            write_remote(sftp, remote_join(original, 'manifest.json'), json.dumps(previous_manifest, indent=2))
        print('\nBereitgestellt:', release_id)
        print('Vorheriger Stand:', remote_join(release, 'previous'))
        if panel is not None:
            panel.start()
        else:
            print('LIVE im Panel starten und Konsole prüfen. Kein /reload verwenden.')
        if mode == 'replace':
            print('Kinetic-Startdatei:', config['server_jar'], '| Java: 25+ | Live-Port bleibt erhalten.')
        print('Rollback: ./scripts/deploy-live.sh rollback', release_id)
    finally:
        if acquired:
            try:
                sftp.remove(remote_join(lock, 'owner.json'))
                sftp.rmdir(lock)
            except Exception:
                print('Hinweis: Deployment-Lock nach Verbindungsabbruch prüfen:', lock, file=sys.stderr)
        # A broken transport must not mask the useful error/backup location from activation.
        with suppress(Exception):
            sftp.close()
        with suppress(Exception):
            client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plugin', 'replace', 'rollback'])
    parser.add_argument('release', nargs='?')
    parser.add_argument('--recover-lock', metavar='RELEASE', help='Nach Prüfung, dass kein Upload läuft: Lock eines abgebrochenen Staging-Uploads sichern')
    parser.add_argument('--config', type=Path, default=PROJECT / 'deploy/live.json')
    parser.add_argument('--auto', action='store_true', help='Live über die Kinetic Client-API stoppen, prüfen und starten')
    parser.add_argument('--dry-run', action='store_true', help='Nur Vorschau; keine Verbindung, kein Build/Stop/Upload')
    parser.add_argument('--live-stopped', action='store_true', help='Bestätigt, dass Live bereits im Panel gestoppt wurde')
    parser.add_argument('--confirm-replace', help='Name des Live-Servers zur Bestätigung des kompletten Ersatzes')
    args = parser.parse_args()
    try:
        run(args)
    except (DeployError, OSError, ValueError, subprocess.CalledProcessError, EOFError) as error:
        print('Deploy abgebrochen:', error, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('\nDeploy unterbrochen. Vor dem Start den Live-Stand/Sicherungsordner prüfen.', file=sys.stderr)
        return 130
    return 0


if __name__ == '__main__':
    sys.exit(main())
