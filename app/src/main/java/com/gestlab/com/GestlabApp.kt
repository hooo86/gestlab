package com.gestlab.com

import de.blinkt.openvpn.core.ICSOpenVPNApplication

/**
 * Application : hérite de celle du moteur OpenVPN (canaux de notification,
 * liaison avec le processus « :openvpn » qui fait tourner le tunnel).
 */
class GestlabApp : ICSOpenVPNApplication()
