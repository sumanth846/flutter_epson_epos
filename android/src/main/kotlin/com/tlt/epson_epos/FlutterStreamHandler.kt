package com.tlt.epson_epos

import io.flutter.plugin.common.EventChannel

class FlutterStreamHandler : EventChannel.StreamHandler {

    var receiver: EventChannel.EventSink? = null

    override fun onCancel(p0: Any?) {
        receiver = null
    }

    override fun onListen(p0: Any?, events: EventChannel.EventSink?) {
        receiver = events
    }


    fun sendEvent(data: Any?) {
        receiver?.success(data)
    }
}