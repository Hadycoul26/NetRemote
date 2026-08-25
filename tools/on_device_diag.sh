#!/bin/bash
# Diagnostic execute contre un appareil Android reel (emulateur de la CI).
#
# Ce script existe parce que livrer une version pour savoir si elle marche
# coute une heure a l'utilisateur et ne repond qu'a une question. Ici, chaque
# execution repond a toutes : ce que l'app voit a l'ecran, ou elle appuie, et
# si l'etat a change.
#
# Il ne s'arrete jamais sur une erreur : un diagnostic incomplet vaut mieux que
# pas de diagnostic. Chaque etape ecrit son resultat dans diag/.

APK=android/app/build/outputs/apk/debug/app-debug.apk
SERVICE=com.example.netremote/com.example.netremote.DataToggleService
mkdir -p diag

say() { echo; echo "=============== $* ==============="; }

say "1. Appareil"
adb shell getprop ro.build.version.release | tee diag/android-version.txt
adb shell getprop ro.product.model

say "2. Installation"
adb install -r -g "$APK"

say "3. Activation du service d'accessibilite"
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
sleep 4
adb shell settings get secure enabled_accessibility_services | tee diag/accessibility.txt

say "4. Etat des donnees au depart"
adb shell settings get global mobile_data | tee diag/state-before.txt

say "5. La bascule marche-t-elle par la voie systeme ?"
# Si meme svc data echoue, le probleme n'est pas dans notre interface.
adb shell svc data disable
sleep 4
adb shell settings get global mobile_data | tee diag/state-after-svc-disable.txt
adb shell svc data enable
sleep 4
adb shell settings get global mobile_data | tee diag/state-after-svc-enable.txt

say "6. Arbre reel du volet des parametres rapides"
adb shell cmd statusbar expand-settings
sleep 3
adb shell uiautomator dump /sdcard/qs.xml
adb pull /sdcard/qs.xml diag/tree-quicksettings.xml
adb exec-out screencap -p > diag/screen-quicksettings.png
adb shell input keyevent KEYCODE_BACK
sleep 2

say "7. Arbre reel de l'ecran reseau des Reglages"
adb shell am start -a android.settings.NETWORK_OPERATOR_SETTINGS
sleep 4
adb shell uiautomator dump /sdcard/net.xml
adb pull /sdcard/net.xml diag/tree-network-settings.xml
adb exec-out screencap -p > diag/screen-network-settings.png

say "8. Ce que NOTRE code voit, la ou uiautomator voit tout"
# La comparaison des deux est le coeur du diagnostic : si uiautomator liste
# l'interrupteur et que nous ne le listons pas, le defaut est dans notre
# lecture, pas dans l'appareil.
adb logcat -c
adb shell am start -n com.example.netremote/.MainActivity --es netremote_test dump_settings
sleep 12
adb logcat -d -s NetRemoteTest:* DataToggleService:* > diag/log-dump-settings.txt

adb logcat -c
adb shell am start -n com.example.netremote/.MainActivity --es netremote_test dump_qs
sleep 12
adb logcat -d -s NetRemoteTest:* DataToggleService:* > diag/log-dump-quicksettings.txt

say "9. La bascule par notre code, de bout en bout"
adb shell settings get global mobile_data | tee diag/state-before-toggle.txt
adb logcat -c
adb shell am start -n com.example.netremote/.MainActivity --es netremote_test selftest --ez on false
sleep 30
adb shell settings get global mobile_data | tee diag/state-after-toggle.txt
adb exec-out screencap -p > diag/screen-after-toggle.png
adb logcat -d -s NetRemoteTest:* DataToggleService:* Recipe:* > diag/log-selftest.txt

say "10. Verdict"
echo -n "avant : "; cat diag/state-before-toggle.txt
echo -n "apres : "; cat diag/state-after-toggle.txt
grep -h "NetRemoteTest" diag/log-selftest.txt | tail -20

exit 0
