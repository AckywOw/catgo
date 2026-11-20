package com.catgo

import android.content.Context

internal class IdentityStore(private val context: Context) {
    fun load(): MfiIdentity {
        val names = context.assets.list("accessory").orEmpty()
        check("identity.pk8" in names && "certificate.p7b" in names) {
            "此安装包缺少内置配件认证材料，请重新安装完整版本"
        }
        val key = context.assets.open("accessory/identity.pk8").use { it.readBytes() }
        try {
            val certificate = context.assets.open("accessory/certificate.p7b").use { it.readBytes() }
            return MfiIdentity(key, certificate)
        } finally {
            key.fill(0)
        }
    }
}
