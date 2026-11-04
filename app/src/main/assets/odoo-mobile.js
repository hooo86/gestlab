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

  var pending = false;
  function run() {
    pending = false;
    if (window.innerWidth >= 768) return;
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
