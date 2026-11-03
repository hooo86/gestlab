package com.gestlab.com

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.ImageView
import android.widget.TextView

/** Menu latéral : une ligne par application Odoo, ses écrans en dessous. */
class MenuAdapter : BaseExpandableListAdapter() {

    private var apps: List<OdooMenu> = emptyList()
    private var entries: List<List<MenuEntry>> = emptyList()

    fun submit(newApps: List<OdooMenu>) {
        apps = newApps
        entries = newApps.map(OdooMenus::entriesOf)
        notifyDataSetChanged()
    }

    override fun getGroupCount() = apps.size
    override fun getChildrenCount(groupPosition: Int) = entries[groupPosition].size
    override fun getGroup(groupPosition: Int): OdooMenu = apps[groupPosition]
    override fun getChild(groupPosition: Int, childPosition: Int): MenuEntry =
        entries[groupPosition][childPosition]

    override fun getGroupId(groupPosition: Int) = apps[groupPosition].id.toLong()
    override fun getChildId(groupPosition: Int, childPosition: Int) =
        getChild(groupPosition, childPosition).menu.id.toLong()

    override fun hasStableIds() = true
    override fun isChildSelectable(groupPosition: Int, childPosition: Int) =
        !getChild(groupPosition, childPosition).isSection

    override fun getChildTypeCount() = 2
    override fun getChildType(groupPosition: Int, childPosition: Int) =
        if (getChild(groupPosition, childPosition).isSection) 0 else 1

    override fun getGroupView(
        groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup
    ): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_menu_app, parent, false)
        val app = apps[groupPosition]
        view.findViewById<TextView>(R.id.app_letter).text =
            app.name.trim().take(1).uppercase()
        view.findViewById<TextView>(R.id.app_name).text = app.name
        view.findViewById<ImageView>(R.id.app_chevron).apply {
            visibility = if (getChildrenCount(groupPosition) > 0) View.VISIBLE else View.INVISIBLE
            rotation = if (isExpanded) 180f else 0f
        }
        return view
    }

    override fun getChildView(
        groupPosition: Int, childPosition: Int, isLastChild: Boolean,
        convertView: View?, parent: ViewGroup
    ): View {
        val entry = getChild(groupPosition, childPosition)
        val view = (convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_menu_entry, parent, false)) as TextView
        val density = parent.resources.displayMetrics.density
        view.text = entry.menu.name
        view.setPadding(
            ((56 + entry.depth * 16) * density).toInt(),
            ((if (entry.isSection) 14 else 10) * density).toInt(),
            (16 * density).toInt(),
            ((if (entry.isSection) 4 else 10) * density).toInt(),
        )
        view.setTypeface(null, if (entry.isSection) Typeface.BOLD else Typeface.NORMAL)
        view.alpha = if (entry.isSection) 0.7f else 1f
        view.textSize = if (entry.isSection) 12f else 15f
        view.isAllCaps = entry.isSection
        return view
    }
}
