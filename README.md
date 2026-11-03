# Gestlab ERP — application Android (Odoo + VPN intégré)

Application Android interne qui ouvre l'ERP Odoo (`http://odoo.lnhc-dz.com:8069`)
à travers un tunnel **OpenVPN intégré**, avec un **menu natif** construit selon
les droits de chaque utilisateur.

- Package : `com.gestlab.com`
- Android 8.0 (API 26) minimum
- Distribution : application privée Google Play (Google Workspace)

## Fonctionnement

1. **Connexion VPN**: au premier lancement, l'utilisateur saisit son identifiant et son
   mot de passe VPN (`auth-user-pass`). Android demande une seule fois l'autorisation VPN.
   Si « Se souvenir de moi » est coché, le mot de passe est chiffré par l'Android Keystore
   et les connexions suivantes sont automatiques.
2. **Tunnel limité à l'application**: seul le trafic de Gestlab passe par le VPN, les
   autres applications du téléphone ne sont pas concernées.
3. **Odoo** s'ouvre dans l'application. L'utilisateur se connecte avec son compte Odoo
   habituel, 2FA comprise.
4. **Menu latéral** ☰: il est lu dans Odoo (`ir.ui.menu.load_menus`). Odoo ne renvoie que les
   applications et menus autorisés par les groupes de l'utilisateur, donc chaque employé
   voit son propre menu.
5. **Téléchargements**: les factures PDF et exports sont enregistrés dans « Téléchargements »
   puis ouverts. Ils passent eux aussi par le VPN.
6. **Coupure réseau**: le VPN se reconnecte tout seul et l'état s'affiche sous le titre.
   Un identifiant refusé ramène à l'écran de connexion.

## Sécurité

- Le fichier `client.ovpn` **n'est jamais dans Git**. Il est injecté à la compilation
  (secret GitHub `OVPN_CONFIG_BASE64`).
- Il ne contient pas de clé privée: sans identifiant et mot de passe valides, il ne
  permet pas de se connecter.
- L'API externe d'OpenVPN pour Android est retirée du manifeste. Aucune autre application
  ne peut piloter le VPN de Gestlab.
- La sauvegarde Android est désactivée (`allowBackup=false`).
- Le HTTP en clair est autorisé **uniquement** pour `odoo.lnhc-dz.com`, dont le trafic
  circule chiffré dans le tunnel. Si Odoo passe en HTTPS, modifiez `odooUrl` dans
  `app/build.gradle.kts`.

## Compiler avec GitHub Actions (recommandé)

1. Encodez le fichier VPN et ajoutez-le comme secret `OVPN_CONFIG_BASE64`
   (GitHub › Settings › Secrets and variables › Actions):
   ```bash
   base64 -w0 client.ovpn
   ```
2. Créez une clé de signature (à conserver précieusement, elle sert pour toutes les mises à jour):
   ```bash
   keytool -genkey -v -keystore gestlab.jks -keyalg RSA -keysize 4096 -validity 10000 -alias gestlab
   ```
   puis ajoutez les secrets `GESTLAB_KEYSTORE_BASE64` (`base64 -w0 gestlab.jks`),
   `GESTLAB_KEYSTORE_PASSWORD`, `GESTLAB_KEY_ALIAS` (`gestlab`) et `GESTLAB_KEY_PASSWORD`.
3. GitHub › Actions › **Gestlab ERP (Android)** › Run workflow.
4. Téléchargez `gestlab-apk` (test sur un téléphone) et `gestlab-aab` (Google Play).

## Compiler sur un poste (Android Studio)

Prérequis: Android SDK, NDK `30.0.14904198`, `swig`, JDK 21, Gradle 9.7.1.

```bash
./scripts/setup-vpnlib.sh
```

```bash
cp /chemin/vers/client.ovpn app/src/main/assets/client.ovpn
```

```bash
gradle :app:assembleDebug
```

## Publication en application privée (Google Workspace)

1. Play Console (compte **Organisation**) › Créer une application.
2. Dans « Tarifs et disponibilité » › *Managed Google Play*, choisissez **Application privée**
   et ajoutez l'ID d'organisation de votre Google Workspace.
3. Remplissez:
   - la **déclaration VPN** (`VpnService`): « accès sécurisé à l'ERP interne de l'entreprise »;
   - la **déclaration de service de premier plan** (`specialUse`, sous-type VPN);
   - la fiche « Sécurité des données » et l'URL d'une politique de confidentialité.
4. Envoyez le fichier `.aab`.
5. Dans la console d'administration Google (Appareils › Applications), mettez l'application
   à disposition des utilisateurs concernés, voire installez-la automatiquement.

## Licence

Le moteur VPN provient d'[ics-openvpn](https://github.com/schwabe/ics-openvpn), sous
GPLv2. Pour un usage interne à l'entreprise, ce n'est en général pas considéré comme une
« distribution ». En cas de diffusion à des tiers (sous-traitants, clients, filiales
distinctes), le code source devrait être fourni: faites valider ce point.

## Personnaliser

| Quoi | Où |
|---|---|
| Adresse d'Odoo | `app/build.gradle.kts` (`odooUrl`) et `res/xml/network_security_config.xml` |
| Nom affiché | `res/values/strings.xml` (`app_name`) |
| Couleurs | `res/values/colors.xml` |
| Icône | `res/drawable/ic_launcher_foreground.xml` (icône provisoire) |
| Version du moteur VPN | `scripts/setup-vpnlib.sh` (`ICS_COMMIT`) |
