#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
NetRemote — serveur PC.

Sert le client web et pilote la connexion Internet de cette machine, pour un
appareil connecte au point d'acces. Le point d'acces lui-meme est active a la
main : ce serveur ne fait qu'ouvrir et couper la connexion.

Sous Windows, activer ou desactiver une carte reseau exige les droits
administrateur. Sans eux, la lecture d'etat fonctionne mais le basculement
echouera : le serveur le dit au demarrage plutot que de le decouvrir au
premier clic.

Usage :
    python server.py                       # detecte la carte active
    python server.py --adapter "Wi-Fi"     # carte imposee
    python server.py --list                # liste les cartes
    python server.py --port 8080

Dependances : aucune, uniquement la bibliotheque standard.
"""

import argparse
import ctypes
import json
import os
import platform
import re
import secrets
import socket
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(os.path.dirname(HERE), 'web')
KEY_FILE = os.path.join(HERE, '.netremote_key')

KEY_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
IS_WINDOWS = platform.system() == 'Windows'


# --------------------------------------------------------------------------
# Cle d'acces
# --------------------------------------------------------------------------

def load_or_create_key():
    """La cle survit aux redemarrages : sinon les clients devraient la ressaisir."""
    if os.path.exists(KEY_FILE):
        with open(KEY_FILE, 'r', encoding='utf-8') as handle:
            key = handle.read().strip()
        if key:
            return key

    key = ''.join(secrets.choice(KEY_ALPHABET) for _ in range(6))
    with open(KEY_FILE, 'w', encoding='utf-8') as handle:
        handle.write(key)
    return key


# --------------------------------------------------------------------------
# Pilotage reseau
# --------------------------------------------------------------------------

def is_admin():
    if not IS_WINDOWS:
        return os.geteuid() == 0 if hasattr(os, 'geteuid') else False
    try:
        return ctypes.windll.shell32.IsUserAnAdmin() != 0
    except Exception:
        return False


def run(args):
    """Execute une commande sans ouvrir de fenetre console."""
    flags = 0
    if IS_WINDOWS:
        flags = subprocess.CREATE_NO_WINDOW
    completed = subprocess.run(
        args, capture_output=True, text=True, encoding='utf-8',
        errors='replace', creationflags=flags
    )
    output = (completed.stdout or '') + (completed.stderr or '')
    return completed.returncode, output.strip()


def powershell(script):
    """
    Renvoie (code, objet_json).

    On passe par PowerShell plutot que par netsh : la sortie de netsh est
    traduite ('Active' / 'Desactive' en francais), donc impossible a analyser
    de facon fiable. Get-NetAdapter expose AdminStatus sous forme numerique,
    identique quelle que soit la langue du systeme.
    """
    code, out = run(['powershell', '-NoProfile', '-NonInteractive', '-Command', script])
    if code != 0 or not out.strip():
        return code, None
    try:
        return code, json.loads(out)
    except ValueError:
        return code, None


def list_adapters():
    """[{name, enabled, connected, description, index}] pour chaque carte."""
    if not IS_WINDOWS:
        code, out = run(['ip', '-o', 'link', 'show'])
        adapters = []
        for line in out.splitlines():
            match = re.match(r'\d+:\s+([^:@]+)[:@]', line)
            if match and match.group(1) != 'lo':
                adapters.append({
                    'name': match.group(1), 'enabled': 'state UP' in line,
                    'connected': 'state UP' in line, 'description': '', 'index': None,
                })
        return adapters

    code, data = powershell(
        'Get-NetAdapter | Select-Object Name,Status,AdminStatus,ifIndex,'
        'InterfaceDescription | ConvertTo-Json -Compress'
    )
    if data is None:
        return []
    if isinstance(data, dict):          # ConvertTo-Json degrade en objet seul
        data = [data]

    adapters = []
    for entry in data:
        adapters.append({
            'name': entry.get('Name', ''),
            # AdminStatus : 1 = activee, 2 = desactivee. Numerique, donc sur.
            'enabled': entry.get('AdminStatus') == 1,
            'connected': entry.get('Status') == 'Up',
            'description': entry.get('InterfaceDescription', ''),
            'index': entry.get('ifIndex'),
        })
    return adapters


def default_route_index():
    """Index de l'interface qui porte la route par defaut, donc l'acces Internet."""
    if not IS_WINDOWS:
        return None
    code, data = powershell(
        "Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | "
        'Sort-Object RouteMetric | Select-Object -First 1 ifIndex | ConvertTo-Json -Compress'
    )
    if isinstance(data, dict):
        return data.get('ifIndex')
    if isinstance(data, list) and data:
        return data[0].get('ifIndex')
    return None


VIRTUAL_HINTS = ('virtualbox', 'vmware', 'hyper-v', 'loopback', 'tap-', 'tunnel', 'bluetooth')


def pick_adapter():
    """
    La carte qui porte la route par defaut, sinon une carte physique active.

    Choisir par le nom serait piegeux : une machine avec VirtualBox expose
    plusieurs cartes nommees 'Ethernet N' qui ne menent nulle part.
    """
    adapters = list_adapters()
    if not adapters:
        return None

    index = default_route_index()
    if index is not None:
        for adapter in adapters:
            if adapter['index'] == index:
                return adapter['name']

    for adapter in adapters:
        description = adapter['description'].lower()
        if adapter['connected'] and not any(hint in description for hint in VIRTUAL_HINTS):
            return adapter['name']

    for adapter in adapters:
        if adapter['enabled']:
            return adapter['name']
    return adapters[0]['name']


def find_adapter(name):
    for adapter in list_adapters():
        if adapter['name'].lower() == (name or '').lower():
            return adapter
    return None


def adapter_state(name):
    adapter = find_adapter(name)
    return None if adapter is None else adapter['enabled']


def adapter_subnets(name):
    """[(reseau_int, masque_int)] des IPv4 portees par la carte."""
    if not IS_WINDOWS:
        return []
    escaped = (name or '').replace("'", "''")
    code, data = powershell(
        "Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias '%s' "
        '-ErrorAction SilentlyContinue | Select-Object IPAddress,PrefixLength | '
        'ConvertTo-Json -Compress' % escaped
    )
    if data is None:
        return []
    if isinstance(data, dict):
        data = [data]

    subnets = []
    for entry in data:
        try:
            address = ipv4_to_int(entry['IPAddress'])
            prefix = int(entry['PrefixLength'])
            mask = (0xFFFFFFFF << (32 - prefix)) & 0xFFFFFFFF
            subnets.append((address & mask, mask))
        except (KeyError, ValueError, TypeError):
            continue
    return subnets


def ipv4_to_int(address):
    parts = [int(p) for p in address.split('.')]
    if len(parts) != 4 or any(p < 0 or p > 255 for p in parts):
        raise ValueError(address)
    return (parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]


def client_reaches_us_through(name, client_ip):
    """
    True si le client parle depuis le reseau porte par cette carte.

    Couper cette carte-la le deconnecterait sans retour possible : c'est
    exactement ce qu'il ne faut pas faire a distance.
    """
    try:
        client = ipv4_to_int(client_ip)
    except ValueError:
        return False

    for network, mask in adapter_subnets(name):
        if client & mask == network:
            return True
    return False


def set_adapter(name, enable):
    if not IS_WINDOWS:
        return run(['ip', 'link', 'set', name, 'up' if enable else 'down'])

    verb = 'Enable-NetAdapter' if enable else 'Disable-NetAdapter'
    escaped = name.replace("'", "''")
    return run([
        'powershell', '-NoProfile', '-NonInteractive', '-Command',
        "%s -Name '%s' -Confirm:$false -ErrorAction Stop" % (verb, escaped),
    ])


# --------------------------------------------------------------------------
# Serveur HTTP
# --------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):

    server_version = 'NetRemote/1.0'

    # Le journal par defaut pollue la console a chaque sondage du client.
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)
        key = (params.get('k') or [''])[0]

        if parsed.path in ('/', '/index.html'):
            return self.send_page()

        if not secrets.compare_digest(key.upper(), self.server.access_key):
            return self.send_json({'error': 'unauthorized'}, status=401)

        if parsed.path == '/api/state':
            return self.send_json(self.build_state())

        if parsed.path == '/api/set':
            wanted = (params.get('on') or ['1'])[0] == '1'
            force = (params.get('force') or ['0'])[0] == '1'
            return self.send_json(self.apply(wanted, force))

        self.send_json({'error': 'not found'}, status=404)

    # --- actions ---

    def build_state(self):
        name = self.server.adapter
        enabled = adapter_state(name)

        warning = ''
        if not self.server.admin:
            warning = ("Serveur lance sans droits administrateur : "
                       "la lecture fonctionne, le basculement echouera.")

        return {
            'platform': 'pc',
            'device': socket.gethostname() + ' — ' + str(name),
            'connected': bool(enabled),
            'detail': 'Carte « %s » : %s' % (
                name, 'activée' if enabled else 'désactivée' if enabled is not None else 'introuvable'
            ),
            'warning': warning,
        }

    def apply(self, enable, force=False):
        name = self.server.adapter
        if not name:
            return {'ok': False, 'detail': 'aucune carte réseau sélectionnée'}

        if not self.server.admin:
            return {'ok': False,
                    'detail': 'droits administrateur requis — relancez le serveur en tant qu\'administrateur'}

        # Garde-fou : couper la carte par laquelle le client nous parle le
        # deconnecterait sans possibilite de rallumer a distance.
        if not enable and not force:
            client_ip = self.client_address[0]
            if client_reaches_us_through(name, client_ip):
                return {
                    'ok': False,
                    'detail': ('refus : vous êtes connecté via la carte « %s ». '
                               'La couper vous déconnecterait définitivement. '
                               'Ajoutez force=1 si c\'est voulu.' % name),
                }

        if self.server.dry_run:
            return {'ok': True,
                    'detail': '[simulation] Carte « %s » aurait été %s'
                              % (name, 'activée' if enable else 'désactivée')}

        code, output = set_adapter(name, enable)
        if code == 0:
            return {'ok': True,
                    'detail': ('Carte « %s » %s' % (name, 'activée' if enable else 'désactivée'))}
        return {'ok': False, 'detail': (output or 'code %d' % code)[:300]}

    # --- reponses ---

    def send_page(self):
        path = os.path.join(WEB_DIR, 'index.html')
        try:
            with open(path, 'rb') as handle:
                body = handle.read()
        except OSError:
            return self.send_json({'error': 'client web introuvable: ' + path}, status=500)

        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)


def local_addresses():
    """Adresses IPv4 de la machine, pour afficher les URL a taper."""
    addresses = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if address not in addresses and not address.startswith('127.'):
                addresses.append(address)
    except Exception:
        pass
    return addresses or ['127.0.0.1']


def main():
    parser = argparse.ArgumentParser(description='NetRemote — serveur PC')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--adapter', help='nom exact de la carte reseau a piloter')
    parser.add_argument('--list', action='store_true', help='liste les cartes et quitte')
    parser.add_argument('--bind', default='0.0.0.0')
    parser.add_argument('--dry-run', action='store_true',
                        help='simule les basculements sans toucher aux cartes')
    args = parser.parse_args()

    if args.list:
        default_index = default_route_index()
        print('Cartes reseau :')
        for adapter in list_adapters():
            marker = ' <-- route par defaut' if adapter['index'] == default_index else ''
            print('  [%s|%s] %-14s %s%s' % (
                'active ' if adapter['enabled'] else 'coupee ',
                'branchee   ' if adapter['connected'] else 'debranchee',
                adapter['name'], adapter['description'], marker,
            ))
        return 0

    adapter = args.adapter or pick_adapter()
    if not adapter:
        print('Aucune carte reseau detectee.', file=sys.stderr)
        return 1

    key = load_or_create_key()
    admin = is_admin()

    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    server.access_key = key
    server.adapter = adapter
    server.admin = admin
    server.dry_run = args.dry_run

    print('NetRemote — serveur PC')
    print('  carte pilotee : %s' % adapter)
    print('  cle d acces   : %s' % key)
    if args.dry_run:
        print('  MODE SIMULATION : aucune carte ne sera reellement basculee.')
    if not admin:
        print('  ATTENTION : lance sans droits administrateur.')
        print('              La lecture d etat marche, le basculement echouera.')
    print('  URL a ouvrir depuis un appareil connecte au point d acces :')
    for address in local_addresses():
        print('    http://%s:%d/?k=%s' % (address, args.port, key))
    print('  Ctrl+C pour arreter.')

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\nArret.')
    finally:
        server.server_close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
