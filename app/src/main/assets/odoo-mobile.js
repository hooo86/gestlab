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
