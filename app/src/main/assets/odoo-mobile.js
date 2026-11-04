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
    // En-têtes de groupe : libellé de la colonne sur chaque total (en tenant compte des colspan).
    var groups = table.querySelectorAll(':scope > tbody > tr.o_group_header');
    for (var g = 0; g < groups.length; g++) {
      var col = 0, gc = groups[g].children;
      for (var k = 0; k < gc.length; k++) {
        var cell = gc[k];
        if (!cell.classList.contains('o_group_name') && cell.tagName === 'TD') {
          if (labels[col]) cell.setAttribute('data-gl-label', labels[col]);
          cell.classList.toggle('gl-empty', !text(cell) && !cell.querySelector('.o_group_pager, button'));
        }
        col += cell.colSpan || 1;
      }
    }
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
   * Barre de commandes : on garde la mise en page d'Odoo et on autorise seulement
   * ses conteneurs « flex » à passer à la ligne au lieu de déborder de l'écran.
   */
  function processControlPanel(cp) {
    var all = [cp].concat(Array.prototype.slice.call(cp.querySelectorAll('*')));
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.closest('.dropdown-menu, .o_searchview_facet, .o_searchview_input_container')) continue;
      var d = getComputedStyle(el).display;
      if (d === 'flex' || d === 'inline-flex') el.classList.add('gl-wrap');
    }
  }

  /*
   * Correction automatique des débordements à gauche : un élément qui commence avant
   * le bord de l'écran est poussé par l'un de ses conteneurs (marge négative, décalage,
   * sens inversé…). On remonte jusqu'au conteneur fautif et on annule ce décalage.
   * Les zones qu'Odoo fait volontairement glisser (kanban, tableaux défilants) sont ignorées.
   */
  window.__glFixes = window.__glFixes || [];
  var SKIP = '.o_kanban_view, .o_kanban_mobile_tabs_container, .dropdown-menu, .modal, .o_field_x2many, .ribbon, .o_widget_web_ribbon';

  function describe(el) {
    var c = (typeof el.className === 'string' ? el.className : '').trim().split(/\s+/).slice(0, 3).join('.');
    return el.tagName.toLowerCase() + (c ? '.' + c : '');
  }

  function scrollsX(el) {
    var o = getComputedStyle(el).overflowX;
    return (o === 'auto' || o === 'scroll') && el.scrollWidth > el.clientWidth + 2;
  }

  function fixElement(el, root) {
    var culprit = null;
    for (var p = el; p && p !== root; p = p.parentElement) {
      if (p.getBoundingClientRect().left < -2) culprit = p; else break;
      if (p.parentElement && scrollsX(p.parentElement)) return;     // décalage dû au défilement
    }
    if (!culprit) return;
    var live = getComputedStyle(culprit), fixed = [];
    var cs = { marginLeft: live.marginLeft, marginRight: live.marginRight, position: live.position, left: live.left,
               transform: live.transform, flexDirection: live.flexDirection, direction: live.direction };
    if (parseFloat(cs.marginLeft) < 0) { culprit.style.setProperty('margin-left', '0', 'important'); fixed.push('ml ' + cs.marginLeft); }
    if (parseFloat(cs.marginRight) < 0) { culprit.style.setProperty('margin-right', '0', 'important'); fixed.push('mr ' + cs.marginRight); }
    if (cs.position !== 'static' && parseFloat(cs.left) < 0) { culprit.style.setProperty('left', '0', 'important'); fixed.push('left ' + cs.left); }
    if (cs.transform && cs.transform !== 'none') { culprit.style.setProperty('transform', 'none', 'important'); fixed.push('transform'); }
    if (cs.flexDirection === 'row-reverse') { culprit.style.setProperty('flex-direction', 'row', 'important'); fixed.push('row-reverse'); }
    if (cs.direction === 'rtl') { culprit.style.setProperty('direction', 'ltr', 'important'); fixed.push('rtl'); }
    // Toujours trop large : on le contraint à la largeur de l'écran.
    if (!fixed.length || culprit.getBoundingClientRect().left < -2) {
      culprit.style.setProperty('max-width', '100%', 'important');
      culprit.style.setProperty('margin-left', '0', 'important');
      var parent = culprit.parentElement;
      if (parent) {
        var pcs = getComputedStyle(parent);
        if (pcs.display.indexOf('flex') >= 0) {
          parent.style.setProperty('flex-wrap', 'wrap', 'important');
          if (/end|right|center/.test(pcs.justifyContent)) parent.style.setProperty('justify-content', 'flex-start', 'important');
          if (pcs.flexDirection === 'row-reverse') parent.style.setProperty('flex-direction', 'row', 'important');
          fixed.push('parent flex ' + pcs.flexDirection + ' ' + pcs.justifyContent);
        }
      }
    }
    if (window.__glFixes.length < 30) window.__glFixes.push(describe(culprit) + ' ← ' + (fixed.join(', ') || 'contraint'));
  }

  function fixLeftOverflow() {
    var root = document.querySelector('.o_action_manager') || document.body;
    var nodes = root.querySelectorAll('.o_control_panel *, .o_form_statusbar *, .oe_button_box *, .o_form_view .o_form_sheet_bg > *');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.closest(SKIP)) continue;
      var r = el.getBoundingClientRect();
      if (r.width === 0 || r.left >= -2) continue;
      fixElement(el, root);
    }
  }

  var pending = false;
  function run() {
    pending = false;
    if (window.innerWidth >= 768) return;
    try { fixLeftOverflow(); } catch (e) {}
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
