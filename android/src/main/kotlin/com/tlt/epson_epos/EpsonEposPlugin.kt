package com.tlt.epson_epos

import android.app.Activity
import androidx.annotation.NonNull
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.epson.epos2.Log as PrintLog;
import com.google.gson.Gson
import com.epson.epos2.Epos2Exception;
import com.epson.epos2.discovery.Discovery;
import com.epson.epos2.discovery.DiscoveryListener;
import com.epson.epos2.discovery.DeviceInfo;
import com.epson.epos2.discovery.FilterOption;
import com.epson.epos2.printer.Printer;
import com.epson.epos2.printer.PrinterStatusInfo;
import com.epson.epos2.printer.PrinterSettingListener
import com.epson.epos2.printer.ReceiveListener


import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import java.lang.Exception
import kotlin.collections.ArrayList
import android.util.Base64
import com.epson.epos2.ConnectionListener
import com.epson.epos2.printer.PrinterInformationListener
import com.epson.epos2.printer.StatusChangeListener

import java.lang.StringBuilder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.*

interface JSONConvertable {
  fun toJSON(): String = Gson().toJson(this)
}

inline fun <reified T : JSONConvertable> String.toObject(): T = Gson().fromJson(this, T::class.java)


class EpsonEposPrinterInfo(
  var ipAddress: String? = null,
  var bdAddress: String? = null,
  var macAddress: String? = null,
  var model: String? = null,
  var type: String? = null,
  var printType: String? = null,
  var target: String? = null
) : JSONConvertable

data class EpsonEposPrinterResult(
  var type: String,
  var success: Boolean,
  var message: String? = null,
  var content: Any? = null,
  var status: Any? = null
) : JSONConvertable

/** EpsonEposPlugin */
class EpsonEposPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
  private lateinit var channel: MethodChannel
  private lateinit var eventChannel: EventChannel
  private var logTag: String = "Epson_ePOS"
  private lateinit var context: Context
  private lateinit var activity: Activity
  private var mPrinter: Printer? = null
  private var mPrinterStatus: PrinterStatusInfo? = null
  private var mTarget: String? = null
  private var printers: MutableList<EpsonEposPrinterInfo> = ArrayList()
  private var streamHandler: FlutterStreamHandler? = null
  val coroutineScope = CoroutineScope(Dispatchers.IO)
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity;
  }
  
  override fun onDetachedFromActivityForConfigChanges() {
  
  }
  
  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
  }
  
  override fun onDetachedFromActivity() {
  
  }
  
  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "epson_epos")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext
    PrintLog.setLogSettings(
      context,
      PrintLog.PERIOD_TEMPORARY,
      PrintLog.OUTPUT_STORAGE,
      null,
      0,
      1,
      PrintLog.LOGLEVEL_LOW
    );
    
    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "epson_epos_events")
    
    this.streamHandler = this.streamHandler
      ?: FlutterStreamHandler()
    
    eventChannel.setStreamHandler(this.streamHandler)
  }
  
  override fun onMethodCall(@NonNull call: MethodCall, @NonNull rawResult: Result) {
    val result = MethodResultWrapper(rawResult)
    Thread(MethodRunner(call, result)).start()
  }
  
  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
  
  inner class MethodRunner(call: MethodCall, result: Result) : Runnable, ReceiveListener {
    private val call: MethodCall = call
    private val result: Result = result
    
    
    override fun onPtrReceive(p0: Printer?, p1: Int, p2: PrinterStatusInfo?, p3: String?) {
      Log.d(logTag, "${p0?.status} p2 $p2 p3 $p3")
      disconnectPrinter()
    }
    
    override fun run() {
      Log.d(logTag, "Method Called: ${call.method}")
      when (call.method) {
        "onDiscovery" -> {
          onDiscovery(call, result)
        }
        
        "onPrint" -> {
          onPrint(call, result)
        }
        
        "onGetPrinterInfo" -> {
          onGetPrinterInfo(call, result)
        }
        
        "isPrinterConnected" -> {
          isPrinterConnected(call, result)
        }
        
        "getPrinterSetting" -> {
          getPrinterSetting(call, result)
        }
        
        "setPrinterSetting" -> {
          setPrinterSetting(call, result)
        }
        
        "getPrinterStatus" -> {
          getPrinterStatus(call, result)
        }
        
        else -> {
          Log.d(logTag, "Method: ${call.method} is not supported yet")
          result.notImplemented()
        }
      }
    }
  }
  
  class MethodResultWrapper(methodResult: Result) : Result {
    
    private val methodResult: Result = methodResult
    private val handler: Handler = Handler(Looper.getMainLooper())
    
    override fun success(result: Any?) {
      handler.post { methodResult.success(result) }
    }
    
    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
      handler.post { methodResult.error(errorCode, errorMessage, errorDetails) }
    }
    
    override fun notImplemented() {
      handler.post { methodResult.notImplemented() }
    }
  }
  
  private fun sendEvent(data: Any?) {
    streamHandler?.sendEvent(data)
  }
  
  
  /**
   * Stop discovery printer
   */
  private fun stopDiscovery() {
    try {
      Discovery.stop()
    } catch (e: Epos2Exception) {
      if (e.errorStatus != Epos2Exception.ERR_PROCESSING) {
      
      }
    }
  }
  
  /**
   * Discovery printers
   */
  private fun onDiscovery(@NonNull call: MethodCall, @NonNull result: Result) {
    val printType: String = call.argument<String>("type") as String
    Log.d(logTag, "onDiscovery type: $printType")
    when (printType) {
      "TCP" -> {
        onDiscoveryTCP(call, result)
      }
      
      "USB" -> {
        onDiscoveryUSB(call, result)
      }
      
      "BT" -> {
        onDiscoveryBLUETOOTH(call, result)
      }
      
      else -> result.notImplemented()
    }
  }
  
  
  private fun onDiscoveryBLUETOOTH(@NonNull call: MethodCall, @NonNull result: Result) {
    printers.clear()
    var filter = FilterOption()
    filter.portType = Discovery.PORTTYPE_BLUETOOTH
    var resp = EpsonEposPrinterResult("onDiscoveryBLUETOOTH", false)
    try {
      Discovery.start(context, filter, mDiscoveryListener)
      Handler(Looper.getMainLooper()).postDelayed({
        resp.success = true
        resp.message = "Successfully!"
        resp.content = printers
        result.success(resp.toJSON())
        stopDiscovery()
      }, 7000)
    } catch (e: Exception) {
      Log.e("OnDiscoveryTCP", "Start not working ${call.method}");
      e.printStackTrace()
      resp.success = false
      resp.message = "Error while search printer"
      result.success(resp.toJSON())
    }
  }
  
  /**
   * Discovery Printers via TCP/IP
   */
  private fun onDiscoveryTCP(@NonNull call: MethodCall, @NonNull result: Result) {
    printers.clear()
    var filter = FilterOption()
    filter.portType = Discovery.PORTTYPE_TCP
    var resp = EpsonEposPrinterResult("onDiscoveryTCP", false)
    try {
      Discovery.start(context, filter, mDiscoveryListener)
      Handler(Looper.getMainLooper()).postDelayed({
        resp.success = true
        resp.message = "Successfully!"
        resp.content = printers
        result.success(resp.toJSON())
        stopDiscovery()
      }, 7000)
    } catch (e: Exception) {
      Log.e("OnDiscoveryTCP", "Start not working ${call.method}");
      e.printStackTrace()
      resp.success = false
      resp.message = "Error while search printer"
      result.success(resp.toJSON())
    }
  }
  
  
  /**
   * Discovery Printers via USB
   */
  private fun onDiscoveryUSB(@NonNull call: MethodCall, @NonNull result: Result) {
    printers.clear()
    var filter = FilterOption()
    filter.portType = Discovery.PORTTYPE_USB
    var resp = EpsonEposPrinterResult("onDiscoveryUSB", false)
    try {
      Discovery.start(context, filter, mDiscoveryListener)
      Handler(Looper.getMainLooper()).postDelayed({
        resp.success = true
        resp.message = "Successfully!"
        resp.content = printers
        result.success(resp.toJSON())
        stopDiscovery()
      }, 1000)
    } catch (e: Exception) {
      Log.e("OnDiscoveryTCP", "Start not working ${call.method}");
      e.printStackTrace()
      resp.success = false
      resp.message = "Error while search printer"
      result.success(resp.toJSON())
    }
  }
  
  private fun onGetPrinterInfo(@NonNull call: MethodCall, @NonNull result: Result) {
    Log.d(logTag, "onGetPrinterInfo $call $result")
  }
  
  private fun isPrinterConnected(@NonNull call: MethodCall, @NonNull result: Result) {
    Log.d(logTag, "isPrinterConnected $call $result")
  }
  
  
  private fun getPrinterStatus(@NonNull call: MethodCall, @NonNull result: Result) {
    Log.d(logTag, "getPrinterStatus $call $result")
    
    val type: String = call.argument<String>("type") as String
    val series: String = call.argument<String>("series") as String
    val target: String = call.argument<String>("target") as String
    
    var resp = EpsonEposPrinterResult("onPrint${type}", false)
    
    try {
      if (mPrinter != null) {
        
        val statusInfo: PrinterStatusInfo? = mPrinterStatus
        
        if (statusInfo?.online == Printer.TRUE) {
          resp.success = true
          resp.message = "online"
        } else if (statusInfo != null) {
          resp.success = false
          resp.message = printerStatusError()
        }
        Log.d(logTag, resp.toJSON())
        result.success(resp.toJSON())
      } else {
        resp.success = false
        resp.message = "Printer connecting"
      }
      
    } catch (e: Exception) {
      e.printStackTrace()
      resp.success = false
      resp.message = "Print error"
      result.success(resp.toJSON())
    }
    
  }
  
  private fun getPrinterSetting(@NonNull call: MethodCall, @NonNull result: Result) {
    Log.d(logTag, "getPrinterSetting $call $result")
    
    val type: String = call.argument<String>("type") as String
    val series: String = call.argument<String>("series") as String
    val target: String = call.argument<String>("target") as String
    
    val resp = EpsonEposPrinterResult("onPrint${type}", false)
    try {
      if (!connectPrinter(target, series)) {
        resp.success = false
        resp.message = printerStatusError()//"Can not connect to the printer."
        result.success(resp.toJSON())
        mPrinter!!.clearCommandBuffer()
      } else {
        if (mPrinter != null) {
          mPrinter!!.clearCommandBuffer()
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
      resp.success = false
      resp.message = "Print error"
      result.success(resp.toJSON())
    }
  }
  
  private fun setPrinterSetting(@NonNull call: MethodCall, @NonNull result: Result) {
    Log.d(logTag, "setPrinterSetting $call $result")
    
    val type: String = call.argument<String>("type") as String
    val series: String = call.argument<String>("series") as String
    val target: String = call.argument<String>("target") as String
    
    val paperWidth: Int? = call.argument<String>("paper_width") as? Int
    val printDensity: Int? = call.argument<String>("print_density") as? Int
    val printSpeed: Int? = call.argument<String>("print_speed") as? Int
    
    var resp = EpsonEposPrinterResult("onPrint${type}", false)
    try {
      if (!connectPrinter(target, series)) {
        resp.success = false
        resp.message = printerStatusError()//"Can not connect to the printer."
        result.success(resp.toJSON())
        mPrinter!!.clearCommandBuffer()
      } else {
        val settingList = HashMap<Int, Int>()
        settingList[Printer.SETTING_PRINTSPEED] = printSpeed ?: Printer.PARAM_DEFAULT
        settingList[Printer.SETTING_PRINTDENSITY] = printDensity ?: Printer.PARAM_DEFAULT
        var pw = 80
        if (paperWidth != null) {
          pw = if (paperWidth != 80 || paperWidth != 58 || paperWidth != 60) {
            80
          } else {
            paperWidth
          }
        }
        settingList[Printer.SETTING_PAPERWIDTH] = pw
        try {
          mPrinter!!.setPrinterSetting(
            Printer.PARAM_DEFAULT,
            settingList,
            mPrinterSettingListener
          )
          
          resp.success = true
          resp.message = "Success"
          result.success(resp.toJSON())
        } catch (ex: Exception) {
          Log.e(logTag, "sendData Error", ex)
          ex.printStackTrace()
          resp.success = false
          resp.message = "Print error"
          result.success(resp.toJSON())
        } finally {
          disconnectPrinter()
        }
        
      }
    } catch (e: Exception) {
      e.printStackTrace()
      resp.success = false
      resp.message = "Print error"
      result.success(resp.toJSON())
    }
  }
  
  private fun getTimstamp(): String {
    val date = java.util.Calendar.getInstance().time
    val dateInString = date.toString()
    
    return "$dateInString *** ${date.time.milliseconds.toString(DurationUnit.SECONDS, 4)}"
  }
  
  /**
   * Print
   */
  private fun onPrint(@NonNull call: MethodCall, @NonNull result: Result) {
    Log.d(logTag, "**** onPrint START ${getTimstamp()}")
    val type: String = call.argument<String>("type") as String
    val series: String = call.argument<String>("series") as String
    val target: String = call.argument<String>("target") as String
    
    val commands: ArrayList<Map<String, Any>> =
      call.argument<ArrayList<Map<String, Any>>>("commands") as ArrayList<Map<String, Any>>
    val resp = EpsonEposPrinterResult("onPrint${type}", false)
    try {
      if (!connectPrinter(target, series)) {
        resp.success = false
        resp.message = "Can not connect to the printer."
        result.success(resp.toJSON())
        Log.e("logTag", "Cannot ConnectPrinter $resp")
        if (mPrinter != null) {
          mPrinter!!.clearCommandBuffer()
        }
      } else {
        commands.forEach {
          onGenerateCommand(it)
        }
        try {
          
          val isError = printerStatusError()
          
          Log.d(logTag, "Status : $isError")
          if (!isError.trim().lowercase().equals("online".trim().lowercase())) {
            resp.success = false
            resp.message = isError
            Log.d(logTag, resp.toJSON())
          } else {
            mPrinter!!.sendData(Printer.PARAM_DEFAULT)
            Log.d(logTag, "Printed $target $series")
            resp.success = true
            resp.message = "Printed $target $series | online"
            Log.d(logTag, resp.toJSON())
          }
          
          result.success(resp.toJSON());
        } catch (ex: Epos2Exception) {
          ex.printStackTrace()
          Log.e(logTag, "sendData Error" + ex.errorStatus, ex)
          resp.success = false
          resp.message = printerStatusError()
          disconnectPrinter()
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
      resp.success = false
      resp.message = printerStatusError()
      disconnectPrinter()
      result.success(resp.toJSON())
    } finally {
      Log.d(logTag, "**** onPrint END ${getTimstamp()}")
    }
  }
  
  private val mDiscoveryListener = DiscoveryListener { deviceInfo ->
    Log.d(logTag, "Found: ${deviceInfo?.deviceName}, ${deviceInfo?.target}, ${deviceInfo}")
    if (deviceInfo?.deviceName != null && deviceInfo?.deviceName != "") {
      var printer = EpsonEposPrinterInfo(
        deviceInfo.ipAddress,
        deviceInfo.bdAddress,
        deviceInfo.macAddress,
        deviceInfo.deviceName,
        deviceInfo.deviceType.toString(),
        deviceInfo.deviceType.toString(),
        deviceInfo.target
      )
      var printerIndex = printers.indexOfFirst { e -> e?.ipAddress == deviceInfo?.ipAddress }
      if (printerIndex > -1) {
        printers[printerIndex] = printer
      } else {
        printers.add(printer)
      }
    }
    
  }
  
  private val mPrinterSettingListener = object : PrinterSettingListener {
    override fun onGetPrinterSetting(p0: Int, p1: Int, p2: Int) {
      try {
        Log.e("logTag", "onGetPrinterSetting type: $p0 $p1 $p2")
        sendEvent("onGetPrinterSetting type: $p0 $p1 $p2")
      } catch (e: Exception) {
        Log.e(logTag, "onGetPrinterSetting Error ${e?.message}", e)
      }
    }
    
    override fun onSetPrinterSetting(p0: Int) {
      try {
        Log.e("logTag", "onSetPrinterSetting Code: $p0")
        sendEvent("onSetPrinterSetting Code: $p0")
      } catch (e: Exception) {
        Log.e(logTag, "onSetPrinterSetting Error ${e?.message}", e)
      }
    }
  }
  
  private fun connectPrinter(target: String, series: String): Boolean {
    Log.d(logTag, "**** connectPrinter ${getTimstamp()}")
    
    val printCons = getPrinterConstant(series)
    if (mPrinter == null || mTarget != target) {
      mPrinter = Printer(printCons, 0, context)
      mTarget = target
    }
    Log.d(logTag, "Connect Printer w $series constant: $printCons via $target")
    try {
      
      if (mPrinterStatus == null || mPrinterStatus?.online != Printer.TRUE) {
        Log.d(logTag, "*** mPrinterStatus if inside")
        
        mPrinter!!.setStatusChangeEventListener { printer, i ->
          Log.d(logTag, "*** setStatusChangeEventListener $printer $i")
          
          mPrinterStatus = printer.status
        }
        
        mPrinter!!.connect(target, Printer.PARAM_DEFAULT)
        
        coroutineScope.launch {
          mPrinterStatus = withContext(Dispatchers.IO, {
            mPrinter!!.startMonitor()
            
            mPrinter!!.status
          })
          
          withContext(Dispatchers.Main, {
            mPrinterStatus = mPrinterStatus
          })
        }
      }
      
      mPrinter!!.clearCommandBuffer()
    } catch (e: Epos2Exception) {
      disconnectPrinter()
      Log.e(logTag, "Connect Error ${e.errorStatus}", e)
      return false
    }
    
    Log.d(logTag, "**** connectPrinter End ${getTimstamp()}")
    return true
  }
  
  private fun disconnectPrinter() {
    if (mPrinter == null) {
      Log.d(logTag, "disconnectPrinter mPrinter null")
      return
    }
    while (true) {
      try {
        if (mPrinter != null) {
          mPrinter!!.stopMonitor()
          mPrinter!!.disconnect()
          mPrinter = null
          mTarget = null
          mPrinterStatus = null
        }
        break
      } catch (e: Exception) {
        Log.e(logTag, "disconnectPrinter Error ${e.message}", e)
        if (mPrinter != null) {
          mPrinter!!.clearCommandBuffer()
        }
        throw e
      }
    }
    mPrinter!!.clearCommandBuffer()
  }
  
  private fun onGenerateCommand(command: Map<String, Any>) {
    if (mPrinter == null) {
      return
    }
    Log.d(logTag, "onGenerateCommand: $command")
    val textData = StringBuilder()
    
    var commandId: String = command["id"] as String
    if (!commandId.isNullOrEmpty()) {
      var commandValue = command["value"]
      
      when (commandId) {
        
        "appendText" -> {
          Log.d(logTag, "appendText: $commandValue")
          mPrinter!!.addText(commandValue.toString());
        }
        
        "printRawData" -> {
          try {
            Log.d(logTag, "printRawData")
            mPrinter!!.addCommand(commandValue as ByteArray)
          } catch (e: Exception) {
            Log.e(logTag, "onGenerateCommand Error" + e.localizedMessage)
          }
        }
        
        "addImage" -> {
          try {
            var width: Int = command["width"] as Int
            var height: Int = command["height"] as Int
            var posX: Int = command["posX"] as Int
            var posY: Int = command["posY"] as Int
            val bitmap: Bitmap? = convertBase64toBitmap(commandValue as String)
            Log.d(logTag, "appendBitmap: $width x $height $posX $posY bitmap $bitmap")
            Printer.SETTING_PAPERWIDTH_80_0
            mPrinter!!.addImage(
              bitmap,
              posX,
              posY,
              width,
              height,
              Printer.PARAM_DEFAULT,
              Printer.PARAM_DEFAULT,
              Printer.PARAM_DEFAULT,
              1.0,
              Printer.COMPRESS_AUTO
            )
          } catch (e: Exception) {
            Log.e(logTag, "onGenerateCommand Error" + e.localizedMessage)
          }
        }
        
        "addFeedLine" -> {
          mPrinter!!.addFeedLine(commandValue as Int)
        }
        
        "addCut" -> {
          when (commandValue.toString()) {
            "CUT_FEED" -> {
              mPrinter!!.addCut(Printer.CUT_FEED)
            }
            
            "CUT_NO_FEED" -> {
              mPrinter!!.addCut(Printer.CUT_NO_FEED)
            }
            
            "CUT_RESERVE" -> {
              mPrinter!!.addCut(Printer.CUT_RESERVE)
            }
            
            else -> {
              mPrinter!!.addCut(Printer.PARAM_DEFAULT)
            }
          }
        }
        
        "addLineSpace" -> {
          mPrinter!!.addFeedLine(commandValue as Int)
        }
        
        "addTextAlign" -> {
          when (commandValue.toString()) {
            "LEFT" -> {
              mPrinter!!.addTextAlign(Printer.ALIGN_LEFT)
            }
            
            "CENTER" -> {
              mPrinter!!.addTextAlign(Printer.ALIGN_CENTER)
            }
            
            "RIGHT" -> {
              mPrinter!!.addTextAlign(Printer.ALIGN_RIGHT)
            }
            
            else -> {
              mPrinter!!.addTextAlign(Printer.PARAM_DEFAULT)
            }
          }
        }
        
        "addTextFont" -> {
          when (commandValue.toString()) {
            "FONT_A" -> {
              mPrinter!!.addTextFont(Printer.FONT_A)
            }
            
            "FONT_B" -> {
              mPrinter!!.addTextFont(Printer.FONT_B)
            }
            
            "FONT_C" -> {
              mPrinter!!.addTextFont(Printer.FONT_C)
            }
            
            "FONT_D" -> {
              mPrinter!!.addTextFont(Printer.FONT_D)
            }
            
            "FONT_E" -> {
              mPrinter!!.addTextFont(Printer.FONT_E)
            }
          }
        }
        
        "addTextSmooth" -> {
          if (commandValue as Boolean) {
            mPrinter!!.addTextSmooth(Printer.TRUE)
          } else {
            mPrinter!!.addTextSmooth(Printer.FALSE)
          }
        }
        
        "addTextSize" -> {
          val width = command["width"] as Int
          val height = command["height"] as Int
          Log.d(logTag, "setTextSize: width: $width, height: $height")
          mPrinter!!.addTextSize(width, height)
        }
        
        "addTextStyle" -> {
          val reverse = command["reverse"] as Boolean?
          val ul = command["ul"] as Boolean?
          val em = command["em"] as Boolean?
          val color = command["color"] as String?
          
          val reverseValue = if (reverse != null) {
            if (reverse) {
              Printer.TRUE
            } else
              Printer.FALSE
          } else {
            Printer.PARAM_DEFAULT
          }
          
          val ulValue = if (ul != null) {
            if (ul) {
              Printer.TRUE
            } else
              Printer.FALSE
          } else {
            Printer.PARAM_DEFAULT
          }
          
          val emValue = if (em != null) {
            if (em) {
              Printer.TRUE
            } else
              Printer.FALSE
          } else {
            Printer.PARAM_DEFAULT
          }
          
          val colorValue = when (color) {
            "COLOR_NONE" -> Printer.COLOR_NONE
            "COLOR_1" -> Printer.COLOR_1
            "COLOR_2" -> Printer.COLOR_2
            "COLOR_3" -> Printer.COLOR_3
            "COLOR_4" -> Printer.COLOR_4
            else -> Printer.PARAM_DEFAULT
          }
          
          mPrinter!!.addTextStyle(reverseValue, ulValue, emValue, colorValue)
        }
      }
    }
  }
  
  private fun getPrinterConstant(series: String): Int {
    return when (series) {
      "TM_M10" -> Printer.TM_M10
      "TM_M30" -> Printer.TM_M30
      "TM_M30II" -> Printer.TM_M30II
      "TM_M50" -> Printer.TM_M50
      "TM_P20" -> Printer.TM_P20
      "TM_P60" -> Printer.TM_P60
      "TM_P60II" -> Printer.TM_P60II
      "TM_P80" -> Printer.TM_P80
      "TM_T20" -> Printer.TM_T20
      "TM_T60" -> Printer.TM_T60
      "TM_T70" -> Printer.TM_T70
      "TM_T81" -> Printer.TM_T81
      "TM_T82" -> Printer.TM_T82
      "TM_T83" -> Printer.TM_T83
      "TM_T83III" -> Printer.TM_T83III
      "TM_T88" -> Printer.TM_T88
      "TM_T90" -> Printer.TM_T90
      "TM_T100" -> Printer.TM_T100
      "TM_U220" -> Printer.TM_U220
      "TM_U330" -> Printer.TM_U330
      "TM_L90" -> Printer.TM_L90
      "TM_H6000" -> Printer.TM_H6000
      else -> 0
    }
  }
  
  private fun convertBase64toBitmap(base64Str: String): Bitmap? {
    val decodedBytes: ByteArray = Base64.decode(base64Str, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
  }
  
  private fun printerStatusError(): String {
    if (mPrinter == null) {
      return getErrorMessage("")
    }
    var errorMes = "";
    val status: PrinterStatusInfo?
    
    if (mPrinterStatus != null) {
      status = mPrinterStatus
    } else {
      status = mPrinter!!.status
    }
    
    if (status?.online == Printer.TRUE) {
      errorMes = getErrorMessage("online")
    }
    
    if (status?.online == Printer.FALSE) {
      errorMes = getErrorMessage("err_offline")
    }
    
    if (status?.connection == Printer.FALSE) {
      errorMes = getErrorMessage("err_no_response")
    }
    
    if (status?.coverOpen == Printer.TRUE) {
      errorMes = getErrorMessage("err_cover_open")
    }
    
    if (status?.paper == Printer.PAPER_EMPTY) {
      errorMes = getErrorMessage("err_receipt_end")
    }
    
    if (status?.paperFeed == Printer.TRUE || status?.panelSwitch == Printer.SWITCH_ON) {
      errorMes = getErrorMessage("err_paper_feed")
    }
    
    if (status?.errorStatus == Printer.UNRECOVER_ERR) {
      errorMes = getErrorMessage("err_unrecover")
    }
    
    if (status?.errorStatus == Printer.MECHANICAL_ERR || status?.errorStatus == Printer.AUTOCUTTER_ERR) {
      errorMes = getErrorMessage("err_autocutter")
      errorMes = getErrorMessage("err_need_recover")
    }
    
    if (status?.errorStatus == Printer.AUTORECOVER_ERR) {
      if (status?.autoRecoverError == Printer.HEAD_OVERHEAT) {
        errorMes = getErrorMessage("err_overheat")
        errorMes = getErrorMessage("err_head")
      }
      if (status?.autoRecoverError == Printer.MOTOR_OVERHEAT) {
        errorMes = getErrorMessage("err_overheat")
        errorMes = getErrorMessage("err_motor")
      }
      if (status?.autoRecoverError == Printer.BATTERY_OVERHEAT) {
        errorMes = getErrorMessage("err_overheat")
        errorMes = getErrorMessage("err_battery")
      }
      if (status?.autoRecoverError == Printer.WRONG_PAPER) {
        errorMes = getErrorMessage("err_wrong_paper")
      }
    }
    if (status?.batteryLevel == Printer.BATTERY_LEVEL_0) {
      errorMes = getErrorMessage("err_battery_real_end")
    }
    
    if (errorMes == "") {
      return getErrorMessage("");
    }
    return errorMes
  }
  
  private fun getErrorMessage(errorKey: String, withNewLine: Boolean = true): String {
    var errorMes = when (errorKey) {
      "warn_receipt_near_end" -> {
        "Roll paper is nearly end."
      }
      
      "online" -> {
        "Online"
      }
      
      "warn_battery_near_end" -> {
        "Battery level of printer is low."
      }
      
      "err_no_response" -> {
        "Please check the connection of the printer and the mobile terminal.\nConnection get lost."
      }
      
      "err_cover_open" -> {
        "Please close roll paper cover."
      }
      
      "err_receipt_end" -> {
        "Please check roll paper."
      }
      
      "err_paper_feed" -> {
        "Please release a paper feed switch."
      }
      
      "err_autocutter" -> {
        "Please remove jammed paper and close roll paper cover.\nRemove any jammed paper or foreign substances in the printer, and then turn the printer off and turn the printer on again."
      }
      
      "err_need_recover" -> {
        "Then, If the printer doesn\'t recover from error, please cycle the power switch."
      }
      
      "err_unrecover" -> {
        "Please cycle the power switch of the printer.\nIf same errors occurred even power cycled, the printer may out of orde"
      }
      
      "err_overheat" -> {
        "Please wait until error LED of the printer turns off. "
      }
      
      "err_head" -> {
        "Print head of printer is hot."
      }
      
      "err_motor" -> {
        "Motor Driver IC of printer is hot."
      }
      
      "err_battery" -> {
        "Battery of printer is hot."
      }
      
      "err_wrong_paper" -> {
        "Please set correct roll paper."
      }
      
      "err_battery_real_end" -> {
        "Please connect AC adapter or change the battery.\nBattery of printer is almost empty."
      }
      
      "err_offline" -> {
        "Printer is offline."
      }
      
      else -> "Unknown error. Please check the power and communication status of the printer."
    }
    if (withNewLine) {
      return "$errorMes\n"
    }
    return errorMes
  }
  
  
}




