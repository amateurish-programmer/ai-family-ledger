package com.familyledger.app.data

/** Public project identifiers only. Provider and administrative secrets stay on the server. */
object CloudEndpoint {
    const val URL = "https://xdgeybztysuvvwagqkvb.supabase.co"
    const val PUBLIC_KEY = "sb_publishable_DMkKHBxMWwQ-j-hWvj-cuw_pE8R4NH-"

    fun requireCompatible(previous: String?, hasCloudIdentity: Boolean) {
        require(!hasCloudIdentity || previous == URL) {
            "本机已关联其他云项目，为保护账本已停止云操作；请先导出备份并联系管理员"
        }
    }
}
