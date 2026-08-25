#!/bin/bash
# Diagnostic execute contre un appareil Android reel (emulateur de la CI).
#
# Ce script existe parce que livrer une version pour savoir si elle marche coute
# une heure a l'utilisateur et ne repond qu'a une question. Ici, chaque
# execution repond a toutes : ce que l'app voit a l'ecran, ou elle appuie, et si
# l'etat a change.
#
# Deux lecons du premier passage, corrigees ici :
#
#  - KEYCODE_BACK ne referme pas le volet des notifications. Il est reste ouvert
#    pendant tout le reste du diagnostic, et les releves suivants ne montraient
#    que SystemUI. On referme donc explicitement, et on VERIFIE quel ecran a le
#    focus avant chaque releve.
#  - Les attentes etaient fixes et trop courtes : les journaux etaient lus avant
#    que l'app ait fini d'ecrire. On attend maintenant la ligne attendue.
#
# Il ne s'arrete jamais sur une erreur : un diagnostic incomplet vaut mieux que
# pas de diagnostic.

APK=android/app/build/outputs/apk/debug/app-debug.apk
SERVICE=com.example.netremote/com.example.netremote.DataToggleService
mkdir -p diag

say() { echo; echo "=============== $* ==============="; }

focus() {
  adb shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus" | tr -d '\r'
}

collapse() {
  adb shell cmd statusbar collapse >/dev/null 2>&1
  sleep 2
}

# Attend qu'un motif apparaisse dans le journal, au lieu de dormir au hasard.
wait_for_log() {
  local pattern="$1" limit="$2" i
  for i in $(seq 1 "$limit"); do
    if adb logcat -d -s NetRemoteTest:I 2>/dev/null | grep -q "$pattern"; then
      echo "  (trouve apres ${i}s)"
      return 0
    fi
    sleep 1
  done
  echo "  (RIEN dans le journal apres ${limit}s)"
  return 1
}

# Lance un test de l'app et rapatrie tout ce qu'il a produit.
run_test() {
  local name="$1" extras="$2" limit="$3"
  say "Test « $name »"
  collapse
  adb logcat -c
  adb shell am start --activity-single-top -n com.example.netremote/.MainActivity --es netremote_test "$name" $extras
  wait_for_log "$name =>" "$limit"
  adb logcat -d -s NetRemoteTest:I DataToggleService:* Recipe:* > "diag/log-$name.txt"
  adb exec-out screencap -p > "diag/screen-$name.png"
  sed 's/^[0-9-]* [0-9:.]* *[0-9]* *[0-9]* //' "diag/log-$name.txt" | grep NetRemoteTest | head -40
}

say "1. Appareil"
adb shell getprop ro.build.version.release | tee diag/android-version.txt
adb shell getprop ro.product.model

say "2. Installation"
adb install -r -g "$APK"
adb shell wm dismiss-keyguard
collapse

say "3. Activation du service d'accessibilite"
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
sleep 5
adb shell settings get secure enabled_accessibility_services | tee diag/accessibility.txt

say "4. La bascule marche-t-elle par la voie systeme ?"
# Si meme svc data echoue, le probleme n'est pas dans notre interface.
adb shell settings get global mobile_data | tee diag/state-before.txt
adb shell svc data disable
sleep 4
adb shell settings get global mobile_data | tee diag/state-after-svc-disable.txt
adb shell svc data enable
sleep 4
adb shell settings get global mobile_data | tee diag/state-after-svc-enable.txt

say "5. Arbre de reference : le volet des parametres rapides"
adb shell cmd statusbar expand-settings
sleep 3
echo "focus : $(focus)" | tee diag/focus-quicksettings.txt
adb shell uiautomator dump /sdcard/qs.xml >/dev/null 2>&1
adb pull /sdcard/qs.xml diag/tree-quicksettings.xml
adb exec-out screencap -p > diag/screen-quicksettings.png
collapse

say "6. Arbre de reference : l'ecran reseau des Reglages"
# C'est LE releve qui manquait : la structure exacte de la rangee « Mobile
# data » — quelles classes, qui est cliquable, qui est cochable.
adb shell am start -a android.settings.NETWORK_OPERATOR_SETTINGS
sleep 6
echo "focus : $(focus)" | tee diag/focus-network.txt
adb shell uiautomator dump /sdcard/net.xml >/dev/null 2>&1
adb pull /sdcard/net.xml diag/tree-network-settings.xml
adb exec-out screencap -p > diag/screen-network-settings.png

say "7. Arbre de reference : les Reglages, ecran d'accueil"
adb shell am start -a android.settings.SETTINGS
sleep 5
echo "focus : $(focus)" | tee diag/focus-settings.txt
adb shell uiautomator dump /sdcard/home.xml >/dev/null 2>&1
adb pull /sdcard/home.xml diag/tree-settings-home.xml

# Ce que NOTRE code voit, la ou uiautomator voit tout. Si uiautomator liste
# l'interrupteur et que nous ne le listons pas, le defaut est dans notre lecture.
run_test dump_settings "" 40
run_test dump_qs "" 40

say "8. La bascule par notre code, de bout en bout"
adb shell settings get global mobile_data | tee diag/state-before-toggle.txt
run_test selftest "--ez on false" 90
adb shell settings get global mobile_data | tee diag/state-after-toggle.txt

say "9. Verdict"
echo -n "mobile_data avant : "; cat diag/state-before-toggle.txt
echo -n "mobile_data apres : "; cat diag/state-after-toggle.txt

exit 0
