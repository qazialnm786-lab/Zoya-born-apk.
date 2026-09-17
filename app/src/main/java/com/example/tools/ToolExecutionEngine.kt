package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.net.URLEncoder

data class ToolExecutionResult(
    val success: Boolean,
    val resultText: String,
    val sassySpokenResponse: String,
    val missingPermission: String? = null
)

class ToolExecutionEngine(private val context: Context) {

    /**
     * Launch an app by package name or common app name.
     */
    fun openApp(appNameOrPackage: String): ToolExecutionResult {
        val trimmed = appNameOrPackage.trim().lowercase()
        val pm = context.packageManager

        // Known common mappings
        val knownPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "gmail" to "com.google.android.gm",
            "calculator" to "com.google.android.calculator",
            "camera" to "com.google.android.GoogleCamera",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "settings" to "com.android.settings",
            "clock" to "com.google.android.deskclock",
            "photos" to "com.google.android.apps.photos"
        )

        var targetPackage = knownPackages[trimmed] ?: appNameOrPackage

        // Check if direct package exists
        var launchIntent = pm.getLaunchIntentForPackage(targetPackage)

        // If not found directly, search installed applications by label
        if (launchIntent == null) {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label.contains(trimmed) || trimmed.contains(label)) {
                    targetPackage = app.packageName
                    launchIntent = pm.getLaunchIntentForPackage(targetPackage)
                    if (launchIntent != null) break
                }
            }
        }

        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ToolExecutionResult(
                success = true,
                resultText = "Successfully opened app $targetPackage",
                sassySpokenResponse = getSassyOpenAppResponse(trimmed)
            )
        } else {
            // Try viewing in Play Store as a fallback
            try {
                val marketIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$targetPackage")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(marketIntent)
                ToolExecutionResult(
                    success = true,
                    resultText = "App not installed. Opened Play Store for $appNameOrPackage",
                    sassySpokenResponse = "Honey, you don't even have $appNameOrPackage installed! I opened the Play Store for you so you can fix that."
                )
            } catch (e: Exception) {
                ToolExecutionResult(
                    success = false,
                    resultText = "Could not find or open app: $appNameOrPackage",
                    sassySpokenResponse = "I tried, babe, but I couldn't find $appNameOrPackage on your phone. Are you sure you downloaded it?"
                )
            }
        }
    }

    /**
     * Search contacts and place a phone call.
     */
    fun searchAndCallContact(contactName: String): ToolExecutionResult {
        // Permission guardrails
        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val hasCall = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContacts) {
            return ToolExecutionResult(
                success = false,
                resultText = "Permission denied: READ_CONTACTS is required.",
                sassySpokenResponse = "Darling, I can't read your mind or your contacts! Turn on Contacts permission so I can see who $contactName is.",
                missingPermission = Manifest.permission.READ_CONTACTS
            )
        }

        val (name, number) = queryContact(contactName)
        if (number == null) {
            return ToolExecutionResult(
                success = false,
                resultText = "No phone number found for contact $contactName",
                sassySpokenResponse = "I checked your contacts, babe, but I couldn't find anyone named $contactName. Double check their spelling for me!"
            )
        }

        return if (hasCall) {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            ToolExecutionResult(
                success = true,
                resultText = "Calling $name at $number",
                sassySpokenResponse = "Calling $name now... hope they're ready for your charming voice!"
            )
        } else {
            // Fallback to dialer if CALL_PHONE is not yet granted
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            ToolExecutionResult(
                success = true,
                resultText = "Dialer opened for $name ($number)",
                sassySpokenResponse = "I opened the dialer for $name. You haven't given me full Call Phone permission yet, so tap call yourself, handsome!",
                missingPermission = Manifest.permission.CALL_PHONE
            )
        }
    }

    /**
     * Deep-link into WhatsApp with pre-filled message.
     */
    fun sendWhatsAppMessage(contactName: String, message: String): ToolExecutionResult {
        var phoneNumber: String? = null

        // Check if contactName is already a phone number
        val digitsOnly = contactName.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 7) {
            phoneNumber = digitsOnly
        } else {
            // Search in contacts
            val hasContacts = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasContacts) {
                val (_, phone) = queryContact(contactName)
                phoneNumber = phone
            }
        }

        val cleanedNumber = phoneNumber?.replace(Regex("[^0-9+]"), "")

        return try {
            val url = if (!cleanedNumber.isNullOrEmpty()) {
                val formattedNumber = if (cleanedNumber.startsWith("+")) {
                    cleanedNumber.substring(1)
                } else {
                    cleanedNumber
                }
                "https://api.whatsapp.com/send?phone=$formattedNumber&text=${URLEncoder.encode(message, "UTF-8")}"
            } else {
                "https://api.whatsapp.com/send?text=${URLEncoder.encode(message, "UTF-8")}"
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                `package` = "com.whatsapp"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check if WhatsApp is installed
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                context.startActivity(intent)
                ToolExecutionResult(
                    success = true,
                    resultText = "WhatsApp message prepared for $contactName",
                    sassySpokenResponse = "Opening WhatsApp for $contactName with your message. Aren't you sweet?"
                )
            } else {
                // Fallback to web browser or generic share
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                ToolExecutionResult(
                    success = true,
                    resultText = "WhatsApp not installed. Opened web link.",
                    sassySpokenResponse = "You don't have WhatsApp installed, so I opened the chat link in your browser instead!"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                resultText = "Failed to launch WhatsApp: ${e.message}",
                sassySpokenResponse = "Oops, something tripped me up while launching WhatsApp. Give it another shot in a sec!"
            )
        }
    }

    /**
     * Send or draft an email via Gmail/Email intent.
     */
    fun sendGmail(recipientEmail: String, subject: String, body: String): ToolExecutionResult {
        return try {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$recipientEmail")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Prefer Gmail if available
            val pm = context.packageManager
            val gmailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$recipientEmail")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                `package` = "com.google.android.gm"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (gmailIntent.resolveActivity(pm) != null) {
                context.startActivity(gmailIntent)
            } else {
                context.startActivity(Intent.createChooser(emailIntent, "Send Email via:").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }

            ToolExecutionResult(
                success = true,
                resultText = "Drafted email to $recipientEmail",
                sassySpokenResponse = "Drafted that email to $recipientEmail with your subject. Look at you being all professional!"
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                resultText = "Failed to launch email: ${e.message}",
                sassySpokenResponse = "I couldn't get Gmail to cooperate just now, babe. Check your email app settings!"
            )
        }
    }

    /**
     * Helper to search contacts provider.
     */
    private fun queryContact(nameQuery: String): Pair<String?, String?> {
        val cr = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")

        var cursor: Cursor? = null
        try {
            cursor = cr.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val contactName = if (nameIndex >= 0) cursor.getString(nameIndex) else nameQuery
                val phoneNumber = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                return Pair(contactName, phoneNumber)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return Pair(null, null)
    }

    private fun getSassyOpenAppResponse(appName: String): String {
        return when {
            appName.contains("youtube") -> "Firing up YouTube! Try not to spend the next three hours watching cat videos, okay?"
            appName.contains("instagram") -> "Opening Instagram for you. Go see what the cool kids are doing."
            appName.contains("calculator") -> "Opening Calculator. Need me to do some heavy math for you, genius?"
            appName.contains("spotify") -> "Opening Spotify! Let's get some decent music playing."
            appName.contains("camera") -> "Opening Camera. Strike your best pose, handsome!"
            else -> "Opening $appName for you. Anything else I can pamper you with?"
        }
    }
}
