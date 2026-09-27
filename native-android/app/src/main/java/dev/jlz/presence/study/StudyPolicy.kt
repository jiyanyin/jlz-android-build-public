package dev.jlz.presence.study

data class StudyPolicy(
    val studyDurationMin: Int = 25,
    val patrolIntervalMin: Int = 5,
    val breakCooldownMin: Int = 8,
    val targetPackages: Set<String> = setOf("com.fenbi.android.servant", "com.openai.chatgpt")
)
