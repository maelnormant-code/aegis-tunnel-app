package com.example.data.local

import java.security.SecureRandom

object PasswordGenerator {
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val NUMBERS = "0123456789"
    private const val SPECIALS = "!@#$%^&*()_-+=<>?/[]{}|"

    fun generate(
        length: Int,
        useUpper: Boolean,
        useLower: Boolean,
        useNumbers: Boolean,
        useSpecials: Boolean
    ): String {
        val charPool = StringBuilder()
        if (useUpper) charPool.append(UPPER)
        if (useLower) charPool.append(LOWER)
        if (useNumbers) charPool.append(NUMBERS)
        if (useSpecials) charPool.append(SPECIALS)

        if (charPool.isEmpty()) {
            return "Pass123!"
        }

        val random = SecureRandom()
        val password = StringBuilder()
        
        val requiredChars = mutableListOf<Char>()
        if (useUpper) requiredChars.add(UPPER[random.nextInt(UPPER.length)])
        if (useLower) requiredChars.add(LOWER[random.nextInt(LOWER.length)])
        if (useNumbers) requiredChars.add(NUMBERS[random.nextInt(NUMBERS.length)])
        if (useSpecials) requiredChars.add(SPECIALS[random.nextInt(SPECIALS.length)])

        val poolString = charPool.toString()
        val remainingLength = length - requiredChars.size
        
        for (i in 0 until remainingLength.coerceAtLeast(0)) {
            password.append(poolString[random.nextInt(poolString.length)])
        }

        for (char in requiredChars) {
            val insertPos = if (password.isEmpty()) 0 else random.nextInt(password.length + 1)
            password.insert(insertPos, char)
        }

        return password.toString().take(length)
    }
}
