package com.noteflowai.app.widget

import android.content.Intent
import android.widget.RemoteViewsService

/**
 * Service that provides data to the widget's ListView via NoteWidgetListProvider.
 */
class NoteWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return NoteWidgetListProvider(applicationContext, intent)
    }
}
