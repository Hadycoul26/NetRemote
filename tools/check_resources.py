#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Verifications statiques sur les ressources et le code, a lancer avant un push.

Le compilateur n'est disponible que sur la CI : ce script rattrape en local les
erreurs qui, sinon, coutent un aller-retour de build complet.

Usage: python tools/check_resources.py
Sortie: code 0 si tout va bien, 1 sinon.
"""

import glob
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = 'android/app/src/main/res'
SRC = 'android/app/src/main/java'
MANIFEST = 'android/app/src/main/AndroidManifest.xml'

# Echappements acceptes par aapt2 dans une valeur de <string>.
VALID_ESCAPES = set("nt'\"\\u@?")

problems = []


def read(path):
    return io.open(path, encoding='utf-8').read()


def check_string_escapes():
    """Une apostrophe nue dans un <string> fait echouer mergeDebugResources."""
    path = os.path.join(RES, 'values', 'strings.xml')
    raw = read(path)

    for match in re.finditer(r'<string name="(\w+)"[^>]*>(.*?)</string>', raw, re.S):
        name, value = match.group(1), match.group(2)
        line = raw[:match.start()].count('\n') + 1

        # Une valeur entierement encadree de guillemets echappe a la regle.
        if value.startswith('"') and value.endswith('"'):
            continue

        for m in re.finditer(r"'", value):
            if m.start() == 0 or value[m.start() - 1] != '\\':
                problems.append(
                    '%s:%d  string/%s : apostrophe non echappee '
                    "(utiliser \\' ) -> ...%s..."
                    % (path, line, name, value[max(0, m.start() - 20):m.start() + 10])
                )
                break

        for m in re.finditer(r'\\(.)', value):
            if m.group(1) not in VALID_ESCAPES:
                problems.append(
                    '%s:%d  string/%s : echappement inconnu "\\%s"'
                    % (path, line, name, m.group(1))
                )
                break


def check_xml_wellformed():
    for path in glob.glob(os.path.join(RES, '**', '*.xml'), recursive=True) + [MANIFEST]:
        try:
            ET.parse(path)
        except Exception as exc:
            problems.append('%s : XML invalide -> %s' % (path, exc))


def check_references():
    """Chaque R.xxx / @xxx doit exister, chaque binding.* doit avoir un @+id."""
    sources = {p: read(p) for p in glob.glob(os.path.join(SRC, '**', '*.kt'), recursive=True)}
    code = '\n'.join(sources.values())

    strings = {e.get('name') for e in ET.parse(os.path.join(RES, 'values', 'strings.xml')).getroot()}
    colors = {e.get('name') for e in ET.parse(os.path.join(RES, 'values', 'colors.xml')).getroot()}
    drawables = {os.path.splitext(os.path.basename(f))[0]
                 for f in glob.glob(os.path.join(RES, 'drawable', '*.xml'))}
    mipmaps = {os.path.splitext(os.path.basename(f))[0]
               for f in glob.glob(os.path.join(RES, 'mipmap-anydpi-v26', '*.xml'))}

    android = '{http://schemas.android.com/apk/res/android}id'
    layout_path = os.path.join(RES, 'layout', 'activity_main.xml')
    ids = {v.split('/')[-1] for v in
           (el.get(android) for el in ET.parse(layout_path).iter()) if v}

    for name in sorted(set(re.findall(r'R\.string\.(\w+)', code))):
        if name not in strings:
            problems.append('R.string.%s : introuvable dans strings.xml' % name)
    for name in sorted(set(re.findall(r'R\.drawable\.(\w+)', code))):
        if name not in drawables:
            problems.append('R.drawable.%s : introuvable' % name)
    for name in sorted(set(re.findall(r'binding\.([a-z]\w+)', code))):
        if name != 'root' and name not in ids:
            problems.append('binding.%s : aucun @+id correspondant dans le layout' % name)

    pools = {'string': strings, 'drawable': drawables, 'mipmap': mipmaps, 'color': colors}
    xml_text = read(layout_path) + read(MANIFEST) + read(
        os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher.xml'))
    for kind, name in sorted(set(re.findall(r'@(string|drawable|mipmap|color)/(\w+)', xml_text))):
        if name not in pools[kind]:
            problems.append('@%s/%s : introuvable' % (kind, name))

    for cls in re.findall(r'android:name="\.(\w+)"', read(MANIFEST)):
        if not any(('class ' + cls) in s for s in sources.values()):
            problems.append('.%s declaree au manifest mais aucune classe correspondante' % cls)

    for path, source in sources.items():
        # Les chaines d'abord : sinon un "http://..." fait passer la fin de la
        # ligne pour un commentaire et les accolades semblent desequilibrees.
        body = re.sub(r'//.*', '', re.sub(r'"[^"\n]*"', '""', source))
        if body.count('{') != body.count('}'):
            problems.append('%s : accolades desequilibrees' % path)
        for imported in re.findall(r'^import .*\.(\w+)$', source, re.M):
            if len(re.findall(r'\b%s\b' % imported, source)) < 2:
                problems.append('%s : import %s inutilise' % (path, imported))


def main():
    check_xml_wellformed()
    check_string_escapes()
    check_references()

    if problems:
        print('%d probleme(s) :' % len(problems))
        for p in problems:
            print('  - ' + p)
        return 1

    print('Toutes les verifications passent.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
