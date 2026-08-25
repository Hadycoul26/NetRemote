#!/bin/bash
# Banc d'essai du pilotage a distance, sur l'emulateur de la CI.
#
# Il ne verifie plus si l'app devine bien l'interface — cette approche est
# abandonnee. Il verifie ce dont le pilotage depend reellement, et rien d'autre :
#
#   1. le serveur repond,
#   2. il rend une image de l'ecran,
#   3. les gestes et les touches systeme CHANGENT cette image.
#
# Le point 3 est le seul qui compte vraiment, et c'est le seul qu'on ne pouvait
# pas verifier avant : deux captures differentes avant et apres une commande
# prouvent que la commande a eu un effet visible. Aucune interpretation, aucune
# supposition sur des libelles.
#
# Il ne s'arrete jamais sur une erreur : un diagnostic incomplet vaut mieux que
# pas de diagnostic.

APK=android/app/build/outputs/apk/debug/app-debug.apk
SERVICE=com.example.netremote/com.example.netremote.DataToggleService
BASE=http://localhost:8080
mkdir -p diag

say() { echo; echo "=============== $* ==============="; }

collapse() {
  adb shell cmd statusbar collapse >/dev/null 2>&1
  sleep 2
}

# Lance un crochet de diagnostic de l'app et rapatrie sa trace.
run_test() {
  local name="$1" extras="$2" limit="$3" i
  say "Crochet « $name »"
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  sleep 1
  adb logcat -c
  adb shell am start --activity-single-top -n com.example.netremote/.MainActivity \
    --es netremote_test "$name" $extras
  for i in $(seq 1 "$limit"); do
    adb logcat -d -s NetRemoteTest:I 2>/dev/null | grep -q "$name =>" && break
    sleep 1
  done
  adb logcat -d -s NetRemoteTest:V RemoteControl:V DataToggleService:V Wake:V \
    > "diag/log-$name.txt"
  sed 's/^[0-9-]* [0-9:.]* *[0-9]* *[0-9]* //' "diag/log-$name.txt" | grep -a NetRemoteTest | head -20
}

# Prend une capture par l'API et rapporte sa taille : zero octet = echec.
capture() {
  local nom="$1"
  curl -s "$BASE/api/screen?w=540&t=$(date +%s%N)" -o "diag/$nom.jpg" -w "  $nom : %{http_code}, %{size_download} octets"
  # Une reponse d'erreur est un fichier elle aussi : sans ce controle, quatre
  # messages JSON identiques passaient pour quatre captures identiques, et le
  # banc annoncait « aucun changement » la ou il n'y avait aucune image.
  if head -c 2 "diag/$nom.jpg" | od -An -tx1 | grep -q "ff d8"; then
    echo " — JPEG valide"
  else
    echo " — PAS UNE IMAGE : $(head -c 140 "diag/$nom.jpg")"
  fi
}

# Deux images identiques = la commande n'a rien change a l'ecran.
compare() {
  local a="diag/$1.jpg" b="diag/$2.jpg" quoi="$3"
  local verdict
  if [ ! -s "$a" ] || [ ! -s "$b" ]; then
    verdict="  $quoi : INDECIDABLE (capture vide)"
  elif cmp -s "$a" "$b"; then
    verdict="  $quoi : AUCUN CHANGEMENT a l'ecran"
  else
    verdict="  $quoi : l'ecran a CHANGE"
  fi
  echo "$verdict"
  echo "$verdict" >> diag/verdict.txt
}

sleep 15

say "1. Appareil"
adb shell getprop ro.build.version.release | tee diag/android-version.txt
adb shell getprop ro.product.model

say "2. Assainir l'emulateur"
# « Pixel Launcher isn't responding » a squatte le premier plan sur trois
# passages et fausse tout ce qu'on mesure ensuite.
adb shell settings put global hide_error_dialogs 1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
collapse

say "3. Installation"
adb install -r -g "$APK"
adb shell wm dismiss-keyguard
collapse

say "4. Service d'accessibilite"
# Il porte les deux capacites du pilotage : la capture et le geste.
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
sleep 5
adb shell settings get secure enabled_accessibility_services | tee diag/accessibility.txt

run_test remote "" 30
run_test serve "" 40

say "5. Le serveur repond-il ?"
# Vider le journal ICI : tout ce qui suit est le fait des appels HTTP, et c'est
# la seule fenetre ou l'on peut voir pourquoi une capture echoue cote serveur.
# Le passage precedent capturait le journal avant les appels, donc ne montrait
# rien de leurs erreurs.
adb logcat -c
adb forward tcp:8080 tcp:8080
curl -s -o diag/info.json -w "  /api/info : %{http_code}\n" "$BASE/api/info"
cat diag/info.json; echo
curl -s -o diag/page.html -w "  page : %{http_code}, %{size_download} octets\n" "$BASE/"

say "6. Rend-il une image de l'ecran ?"
adb shell input keyevent KEYCODE_HOME
sleep 2
capture accueil
file diag/accueil.jpg 2>/dev/null || true

say "7. Une touche systeme change-t-elle l'ecran ?"
# Le volet des parametres rapides : c'est de la que l'utilisateur coupera ses
# donnees, donc c'est le chemin exact qu'il faut prouver.
curl -s "$BASE/api/key?name=quicksettings"; echo
sleep 3
capture volet
compare accueil volet "ouverture du volet"

say "8. Un geste change-t-il l'ecran ?"
# Un balayage vers le bas au milieu du volet ouvert.
curl -s "$BASE/api/swipe?x1=540&y1=1600&x2=540&y2=700&ms=300"; echo
sleep 3
capture apres_geste
compare volet apres_geste "balayage"

say "9. Retour a l'accueil"
curl -s "$BASE/api/key?name=home"; echo
sleep 3
capture retour_accueil
compare volet retour_accueil "retour a l'accueil"

say "10. Reveil de l'ecran"
adb shell input keyevent KEYCODE_SLEEP
sleep 3
curl -s "$BASE/api/wake"; echo
sleep 2
adb shell dumpsys power 2>/dev/null | grep -m1 -i "mWakefulness=" | tr -d '\r' | tee diag/wakefulness.txt

say "11. Journal des appels HTTP"
# SANS filtre de tag : le passage precedent a rendu un journal vide alors que
# chaque echec de capture est cense s'y inscrire. Un filtre qui ne trouve rien
# et un code qui ne s'execute pas donnent le meme fichier vide — on enleve donc
# le filtre plutot que de continuer a deviner lequel des deux c'est.
adb logcat -d > diag/log-complet.txt
grep -a -iE "netremote|RemoteControl|WebServer|Screenshot|accessibility" diag/log-complet.txt   | tail -60 > diag/log-http.txt
sed 's/^[0-9-]* [0-9:.]* *[0-9]* *[0-9]* //' diag/log-http.txt | head -40

say "12. Verdict"
echo "Les trois lignes qui comptent :"
grep -h "l'ecran a CHANGE\|AUCUN CHANGEMENT\|INDECIDABLE" diag/*.txt 2>/dev/null || true
ls -l diag/*.jpg 2>/dev/null

exit 0
