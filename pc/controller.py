#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
NetRemote — contrôleur PC.

Quand le téléphone héberge le point d'accès, il est la passerelle par défaut de
cette machine. Il n'y a donc aucune adresse à chercher ni à taper : on lit la
route par défaut et on parle à ce qui s'y trouve.

Usage :
    python controller.py                 # état + menu
    python controller.py --on            # active la connexion
    python controller.py --off           # coupe la connexion
    python controller.py --host 1.2.3.4  # cible imposée
"""

import argparse
import json
import os
import platform
import re
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
KEY_CACHE = os.path.join(HERE, '.netremote_client_key')
DEFAULT_PORT = 8080
TIMEOUT = 15

IS_WINDOWS = platform.system() == 'Windows'


def run(args):
    flags = subprocess.CREATE_NO_WINDOW if IS_WINDOWS else 0
    done = subprocess.run(args, capture_output=True, text=True,
                          encoding='utf-8', errors='replace', creationflags=flags)
    return done.returncode, (done.stdout or '') + (done.stderr or '')


def default_gateway():
    """Adresse de la passerelle : c'est l'appareil qui partage sa connexion."""
    if IS_WINDOWS:
        code, out = run([
            'powershell', '-NoProfile', '-NonInteractive', '-Command',
            "Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | "
            'Sort-Object RouteMetric | Select-Object -First 1 -ExpandProperty NextHop'
        ])
        candidate = out.strip().splitlines()[0].strip() if out.strip() else ''
        return candidate if re.match(r'^\d+\.\d+\.\d+\.\d+$', candidate) else None

    code, out = run(['ip', 'route', 'show', 'default'])
    match = re.search(r'default via (\d+\.\d+\.\d+\.\d+)', out)
    return match.group(1) if match else None


def load_key():
    if os.path.exists(KEY_CACHE):
        with open(KEY_CACHE, 'r', encoding='utf-8') as handle:
            return handle.read().strip()
    return ''


def save_key(key):
    with open(KEY_CACHE, 'w', encoding='utf-8') as handle:
        handle.write(key)


def call(host, port, path, params):
    query = '&'.join('%s=%s' % (k, v) for k, v in params.items())
    url = 'http://%s:%d%s?%s' % (host, port, path, query)
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as response:
            return json.loads(response.read().decode('utf-8'))
    except urllib.error.HTTPError as error:
        if error.code == 401:
            return {'error': 'unauthorized'}
        return {'error': 'HTTP %d' % error.code}
    except Exception as error:
        return {'error': type(error).__name__}


def show_state(state):
    if 'error' in state:
        # Ne jamais echouer en silence : sans ce message, l'utilisateur croit
        # que la commande n'a rien renvoye alors que c'est la relecture qui a rate.
        print('  état illisible (%s)' % state['error'])
        return False

    print('  appareil : %s (%s)' % (state.get('device', '?'), state.get('platform', '?')))
    print('  état     : %s' % ('CONNECTÉ' if state.get('connected') else 'COUPÉ'))
    print('  détail   : %s' % state.get('detail', ''))
    if state.get('warning'):
        print('  ATTENTION: %s' % state['warning'])
    print('  commandes: --on (activer)   --off (couper)')
    return True


def main():
    parser = argparse.ArgumentParser(description='NetRemote — contrôleur')
    parser.add_argument('--host', help='adresse de la cible (sinon : la passerelle)')
    parser.add_argument('--port', type=int, default=DEFAULT_PORT)
    parser.add_argument('--key', help='clé affichée par la cible')
    parser.add_argument('--on', action='store_true')
    parser.add_argument('--off', action='store_true')
    args = parser.parse_args()

    host = args.host or default_gateway()
    if not host:
        print('Aucune passerelle : es-tu connecté au point d\'accès ?', file=sys.stderr)
        return 1

    key = args.key or load_key()
    if not key:
        print('Cible détectée : %s' % host)
        key = input('Clé affichée sur l\'appareil : ').strip().upper()
    if not key:
        print('Clé requise.', file=sys.stderr)
        return 1

    print('Cible : %s:%d' % (host, args.port))
    state = call(host, args.port, '/api/state', {'k': key})

    if state.get('error') == 'unauthorized':
        print('Clé refusée.', file=sys.stderr)
        return 1
    if 'error' in state:
        print('Injoignable (%s). La cible est-elle allumée et le serveur actif ?'
              % state['error'], file=sys.stderr)
        return 1

    save_key(key)
    show_state(state)

    if not args.on and not args.off:
        return 0

    wanted = '1' if args.on else '0'
    print('\nEnvoi de la commande…')
    result = call(host, args.port, '/api/set', {'k': key, 'on': wanted})

    if 'error' in result:
        print('  ÉCHEC : %s' % result['error'], file=sys.stderr)
        return 1

    print('  %s %s' % ('OK   :' if result.get('ok') else 'ÉCHEC:', result.get('detail', '')))

    print('\nÉtat après commande :')
    show_state(call(host, args.port, '/api/state', {'k': key}))
    return 0 if result.get('ok') else 1


if __name__ == '__main__':
    sys.exit(main())
