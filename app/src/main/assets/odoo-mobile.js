/*
 * Gestlab — affichage mobile d'Odoo : injecte odoo-mobile.css (window.__GL_CSS) et
 * transforme les listes principales en cartes (étiquette de colonne sur chaque valeur).
 */
(function () {
  if (window.__glMobile) return;
  window.__glMobile = true;

  // Odoo doit être rendu à la largeur réelle de l'écran.
  if (!document.querySelector('meta[name="viewport"]')) {
    var meta = document.createElement('meta');
    meta.name = 'viewport';
    meta.content = 'width=device-width, initial-scale=1';
    (document.head || document.documentElement).appendChild(meta);
  }

  var style = document.createElement('style');
  style.id = 'gestlab-mobile';
  style.textContent = window.__GL_CSS || '';
  (document.head || document.documentElement).appendChild(style);

  var X2MANY = '.o_field_x2many, .o_field_one2many, .o_field_many2many';

  function text(el) { return (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim(); }

  function isEmpty(td) {
    return !text(td) && !td.querySelector('input, img, svg, i, .badge, .o_field_badge, .o_priority, .o_field_boolean');
  }

  function processTable(table) {
    if (table.closest(X2MANY)) return;          // lignes modifiables : garder le tableau
    table.classList.add('gl-cards');
    var heads = table.querySelectorAll(':scope > thead > tr > th');
    var labels = [];
    for (var i = 0; i < heads.length; i++) labels.push(text(heads[i]));
    var rows = table.querySelectorAll(':scope > tbody > tr.o_data_row');
    for (var r = 0; r < rows.length; r++) {
      var cells = rows[r].children, titleDone = false;
      for (var c = 0; c < cells.length; c++) {
        var td = cells[c];
        if (!td.classList.contains('o_data_cell')) continue;
        td.setAttribute('data-gl-label', labels[c] || '');
        td.classList.toggle('gl-empty', isEmpty(td));
        if (!titleDone && !td.classList.contains('gl-empty')) {
          td.classList.add('gl-title');
          titleDone = true;
        } else {
          td.classList.remove('gl-title');
        }
      }
    }
  }

  /*
   * Barre de commandes : chaque élément est repéré par son rôle (et non par sa place
   * dans la page), puis replacé dans une grille CSS. Les conteneurs intermédiaires
   * passent en « display: contents » pour ne plus influencer la mise en page.
   */
  var CP_ROLES = [
    ['gl-cp-crumb',   '.breadcrumb, .o_breadcrumb'],
    ['gl-cp-actions', '.o_cp_action_menus'],
    ['gl-cp-buttons', '.o_cp_buttons, .o_control_panel_main_buttons'],
    ['gl-cp-views',   '.o_cp_switch_buttons'],
    ['gl-cp-toggle',  '.o_enable_searchview, .o_toggle_searchview_full'],
    ['gl-cp-search',  '.o_cp_searchview, .o_searchview, .o_search_options'],
    ['gl-cp-pager',   '.o_cp_pager, .o_pager']
  ];
  var CP_ITEM_CLASSES = CP_ROLES.map(function (r) { return r[0]; });

  function isItem(el) {
    for (var i = 0; i < CP_ITEM_CLASSES.length; i++) if (el.classList.contains(CP_ITEM_CLASSES[i])) return true;
    return false;
  }

  function processControlPanel(cp) {
    if (cp.closest('.modal')) return;
    // Réinitialisation (Odoo re-dessine souvent la barre).
    cp.querySelectorAll('.gl-cp-wrap, .gl-cp-item, .gl-cp-other').forEach(function (el) {
      el.classList.remove('gl-cp-wrap', 'gl-cp-item', 'gl-cp-other');
      CP_ITEM_CLASSES.forEach(function (c) { el.classList.remove(c); });
    });
    var found = 0;
    CP_ROLES.forEach(function (role) {
      var nodes = cp.querySelectorAll(role[1]);
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el.parentElement && el.parentElement.closest('.gl-cp-item')) continue;   // déjà dans un élément repéré
        if (isItem(el)) continue;
        el.classList.add('gl-cp-item', role[0]);
        found++;
        for (var p = el.parentElement; p && p !== cp; p = p.parentElement) p.classList.add('gl-cp-wrap');
      }
    });
    if (!found) { cp.classList.remove('gl-cp'); return; }
    // Tout autre élément visible devient une ligne pleine largeur.
    var wraps = [cp].concat(Array.prototype.slice.call(cp.querySelectorAll('.gl-cp-wrap')));
    wraps.forEach(function (w) {
      for (var i = 0; i < w.children.length; i++) {
        var c = w.children[i];
        if (!c.classList.contains('gl-cp-wrap') && !c.classList.contains('gl-cp-item')) c.classList.add('gl-cp-other');
      }
    });
    cp.classList.add('gl-cp');
  }

  var pending = false;
  function run() {
    pending = false;
    if (window.innerWidth >= 768) return;
    var cps = document.querySelectorAll('.o_control_panel');
    for (var k = 0; k < cps.length; k++) {
      try { processControlPanel(cps[k]); } catch (e) {}
    }
    // Conteneurs parfois défilés horizontalement par Odoo : on les ramène au début.
    var scrolled = document.querySelectorAll('.o_control_panel, .o_form_statusbar, .oe_button_box, .o_form_sheet_bg');
    for (var m = 0; m < scrolled.length; m++) if (scrolled[m].scrollLeft) scrolled[m].scrollLeft = 0;
    var tables = document.querySelectorAll('table.o_list_table, table.o_list_view');
    for (var i = 0; i < tables.length; i++) {
      try { processTable(tables[i]); } catch (e) {}
    }
  }
  function schedule() {
    if (pending) return;
    pending = true;
    setTimeout(run, 120);
  }

  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  window.addEventListener('resize', schedule);
  schedule();
})();
