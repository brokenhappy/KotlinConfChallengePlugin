package com.github.brokenhappy.kotlinconfchallengeplugin.services

import com.github.brokenhappy.kotlinconfchallengeplugin.shared.Blob
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection

internal suspend fun downloadChallengesCsv(sheetId: String): String? =
    withContext(Dispatchers.IO) {
        HttpRequests
            .request("https://docs.google.com/spreadsheets/d/$sheetId/export?format=csv")
            .throwStatusCodeException(false)
            .connect { request ->
                val code = (request.connection as HttpURLConnection).responseCode
                if (code in 200..299) request.readString() else null
            }
    }

internal suspend fun downloadImage(imageUrl: String): Blob? {
    if (imageUrl.isBlank()) return null
    return withContext(Dispatchers.IO) {
        HttpRequests
            .request(imageUrl)
            .throwStatusCodeException(false)
            .connect { request ->
                val code = (request.connection as HttpURLConnection).responseCode
                if (code in 200..299) Blob.fromByteArray(request.readBytes(null)) else null
            }
    }
}
