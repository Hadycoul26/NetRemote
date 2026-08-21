# NetRemote

Piloter la connexion Internet d'une machine depuis un autre appareil connecté à
son point d'accès. Le point d'accès est activé **à la main** : NetRemote ne fait
qu'ouvrir et couper la connexion.

```
┌─────────────────────┐         point d'accès          ┌──────────────────┐
│  machine qui         │◄───────  (activé à la main) ───│  client          │
│  partage sa          │                                │  navigateur web  │
│  connexion           │   http://<ip>:8080/?k=CLE      │  PC ou Android   │
│  = le serveur        │                                └──────────────────┘
└─────────────────────┘
```

Le serveur existe en deux versions, selon la machine qui partage :

| Version | État | Ce qu'elle pilote |
|---|---|---|
| **Android** (`android/`) | c'est celle qui sert | données mobiles du téléphone |
| **PC** (`pc/server.py`) | optionnel, testé | une carte réseau Windows |

**La version Android est la principale** : c'est le téléphone qui porte la
connexion cellulaire et la partage par point d'accès.

La version PC est conservée pour le cas d'une **clé 4G USB**, qui apparaît sous
Windows comme une carte réseau ordinaire. Elle ne sert à rien pour un modem
cellulaire *intégré* (`netsh mbn`), non géré faute de matériel pour le tester.

Le client web (`web/index.html`) est **le même pour les deux** : les serveurs
exposent la même API, donc une seule page à maintenir.

## Serveur PC

Aucune dépendance : bibliothèque standard Python uniquement.

```
python pc/server.py --list                    # voir les cartes réseau
python pc/server.py                           # démarrer
python pc/server.py --adapter "Wi-Fi"         # carte imposée
python pc/server.py --dry-run                 # simuler, sans rien basculer
```

Au démarrage, le serveur affiche la clé d'accès et les URL à ouvrir.

**Les droits administrateur sont obligatoires** pour activer ou couper une carte.
Sans eux, la lecture d'état fonctionne mais le basculement échoue — le serveur
le dit au démarrage plutôt que de te le faire découvrir au premier clic.

### Choix de la carte

Le serveur prend celle qui porte la **route par défaut**, pas celle dont le nom
ressemble à « Wi-Fi ». Sur une machine avec VirtualBox, plusieurs cartes
s'appellent `Ethernet N` et ne mènent nulle part : choisir par le nom se
tromperait. Utilise `--list` pour voir laquelle est retenue.

### Lecture d'état indépendante de la langue

`netsh interface show interface` renvoie « Activé » / « Désactivé » sur un
Windows français, « Enabled » / « Disabled » ailleurs. Analyser cette sortie
est impossible de façon fiable. NetRemote lit `Get-NetAdapter` et se fie à
`AdminStatus`, qui vaut 1 ou 2 quelle que soit la langue.

## Sécurité

**Clé d'accès obligatoire** — n'importe qui connecté au point d'accès peut
joindre le serveur, et sans clé pourrait couper la connexion. Elle est générée
au premier démarrage et conservée dans `pc/.netremote_key`. Supprime ce fichier
pour en obtenir une nouvelle.

**Garde-fou anti-auto-déconnexion** — le serveur refuse de couper la carte par
laquelle le client lui parle : ça le déconnecterait sans aucun moyen de rallumer
à distance. Ajouter `force=1` à la requête passe outre, si c'est voulu.

**HTTP en clair** — la clé circule non chiffrée sur le réseau local. Acceptable
sur un point d'accès privé dont tu contrôles les participants ; pas au-delà.

## API

Les deux serveurs exposent la même chose. `k` est la clé d'accès.

| Route | Réponse |
|---|---|
| `GET /` | le client web |
| `GET /api/state?k=CLE` | `{platform, device, connected, detail, warning}` |
| `GET /api/set?k=CLE&on=1` | `{ok, detail}` — active la connexion |
| `GET /api/set?k=CLE&on=0` | idem, coupe la connexion |
| `GET /api/set?k=CLE&on=0&force=1` | passe outre le garde-fou |

Une clé absente ou fausse renvoie `401`.

## Limite commune aux deux versions

**NetRemote ne peut pas allumer le point d'accès.** S'il est éteint, il n'y a
aucun réseau par lequel joindre le serveur. C'est une contrainte de principe, pas
d'implémentation : il faut l'activer à la main, ou par SMS avec un outil séparé.
