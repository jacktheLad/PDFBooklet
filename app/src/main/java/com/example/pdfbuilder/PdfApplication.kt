package com.example.pdfbuilder

import android.app.Application
import com.umeng.commonsdk.UMConfigure
import com.umeng.analytics.MobclickAgent

class PdfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Umeng SDK
        // Pre-init: Context, AppKey, Channel
        UMConfigure.preInit(this, "697f44f09a7f3764884c6f52", "Umeng")
        
        // Init: Context, AppKey, Channel, DeviceType, PushSecret
        // DeviceType: UMConfigure.DEVICE_TYPE_PHONE (1)
        // PushSecret: null (we are not using push)
        UMConfigure.init(this, "697f44f09a7f3764884c6f52", "Umeng", UMConfigure.DEVICE_TYPE_PHONE, null)
        
        // Choose AUTO page collection mode
        MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.AUTO)
    }
}
