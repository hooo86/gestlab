#!/usr/bin/env bash
# Récupère le moteur OpenVPN (ics-openvpn) à une version figée et le prépare
# pour être utilisé comme bibliothèque par l'application.
# À lancer une fois avant la première compilation (et après un changement de ICS_COMMIT).
set -euo pipefail

ICS_COMMIT=660901ce320539aec476bd04bd1999c396d5e778   # ics-openvpn, 3 nov. 2026
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/vendor/ics-openvpn"

if [ ! -d "$DEST/.git" ]; then
  mkdir -p "$DEST"
  git -C "$DEST" init -q
  git -C "$DEST" remote add origin https://github.com/schwabe/ics-openvpn.git
fi
git -C "$DEST" fetch -q --depth 1 origin "$ICS_COMMIT"
git -C "$DEST" checkout -q -f FETCH_HEAD
git -C "$DEST" submodule sync -q
git -C "$DEST" submodule update --init --recursive --depth 1 \
  || git -C "$DEST" submodule update --init --recursive

MAIN="$DEST/main"
# 1. Le module « application » d'ics-openvpn devient une bibliothèque.
cp "$ROOT/vpnlib/build.gradle.kts" "$MAIN/build.gradle.kts"
# 2. Le profil VPN (identifiant + mot de passe) est chiffré sur le téléphone :
#    on remplace l'implémentation factice de la variante « skeleton ».
SK="$MAIN/src/skeleton/java/de/blinkt/openvpn/core"
rm -f "$SK/ProfileEncryption.java"
cp "$MAIN/src/ui/java/de/blinkt/openvpn/core/ProfileEncryption.kt" "$SK/ProfileEncryption.kt"

echo "ics-openvpn prêt dans $DEST"
