package com.familyledger.app.data

/** Public project identifiers only. Provider and administrative secrets stay on the server. */
object CloudEndpoint {
    const val URL = "https://xdgeybztysuvvwagqkvb.supabase.co"
    const val TRANSPORT_URL = "https://family-ledger-gateway.pages.dev"
    const val PUBLIC_KEY = "sb_publishable_DMkKHBxMWwQ-j-hWvj-cuw_pE8R4NH-"

    fun prefersDirect(cellular: Boolean, wifi: Boolean, vpn: Boolean): Boolean = cellular && !wifi && !vpn

    /** Keep stored project identity stable while changing only the HTTPS transport. */
    fun requestUrl(projectUrl: String, path: String, preferDirect: Boolean = false): String {
        require(projectUrl == URL) { "云项目不匹配，已停止请求" }
        require(path.startsWith("/") && !path.startsWith("//") &&
            path.none { it == '\\' || it == '#' || it.isISOControl() }) { "云端请求路径无效" }
        return (if (preferDirect) URL else TRANSPORT_URL) + path
    }

    fun requireCompatible(previous: String?, hasCloudIdentity: Boolean) {
        require(!hasCloudIdentity || previous == URL) {
            "本机已关联其他云项目，为保护账本已停止云操作；请先导出备份并联系管理员"
        }
    }
}
