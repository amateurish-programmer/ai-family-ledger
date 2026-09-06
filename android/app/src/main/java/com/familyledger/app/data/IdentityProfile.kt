package com.familyledger.app.data

data class ProfileChoice(val id: String, val label: String, val symbol: String)

/** Display choices never grant family membership or owner permissions. */
object IdentityProfile {
    val familyIcons = listOf(
        ProfileChoice("home", "小家", "🏡"), ProfileChoice("heart", "爱心", "💛"),
        ProfileChoice("tree", "大树", "🌳"), ProfileChoice("sun", "阳光", "☀️"),
    )
    val avatars = listOf(
        ProfileChoice("person", "本人", "🙂"), ProfileChoice("man", "男士", "👨"),
        ProfileChoice("woman", "女士", "👩"), ProfileChoice("child", "孩子", "🧒"),
        ProfileChoice("elder", "长辈", "🧓"), ProfileChoice("cat", "小猫", "🐱"),
    )
    fun familyName(value: String): String = value.trim().also {
        require(it.codePointCount(0, it.length) in 1..80 && !it.any { c -> c.isISOControl() }) { "家庭名称须为 1 至 80 个字符，不能包含换行或控制字符" }
    }
    fun familyIcon(value: String?): String = (value ?: "home").also {
        require(familyIcons.any { option -> option.id == it }) { "请选择列表中的家庭图标" }
    }
    fun avatar(value: String?): String = (value ?: "person").also {
        require(avatars.any { option -> option.id == it }) { "请选择列表中的角色头像" }
    }
}
