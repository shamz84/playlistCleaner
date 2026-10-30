package app.playlistplayer.feature.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun message(error: OnboardingError) = context.getString(OnboardingText.errorMessage(error))

    @Test
    fun `NotFoundOrNotShared asks to check the id and the sharing setting`() {
        assertThat(message(OnboardingError.NotFoundOrNotShared)).isEqualTo(
            "Couldn't open that file. Check the ID and make sure the file is shared as 'Anyone with the link'.",
        )
    }

    @Test
    fun `NotShared explains the sharing setting`() {
        assertThat(message(OnboardingError.NotShared))
            .isEqualTo("The file isn't shared. In Google Drive, set General access to 'Anyone with the link'.")
    }

    @Test
    fun `QuotaExceeded asks to retry later`() {
        assertThat(message(OnboardingError.QuotaExceeded))
            .isEqualTo("Google Drive is limiting downloads right now. Try again in a few minutes.")
    }

    @Test
    fun `Network points at connectivity`() {
        assertThat(message(OnboardingError.Network)).isEqualTo("Couldn't connect. Check the internet connection and try again.")
    }

    @Test
    fun `InvalidInput says the file is not a playlist`() {
        assertThat(message(OnboardingError.InvalidInput)).isEqualTo("That isn't a playable M3U playlist. Check the ID or URL.")
    }

    @Test
    fun `AccountExpired points at the provider`() {
        assertThat(message(OnboardingError.AccountExpired)).isEqualTo(
            "The provider account for this playlist has expired. Renew it with your provider, then try again.",
        )
    }

    @Test
    fun `every error has a distinct message`() {
        val messages = OnboardingError.entries.map(::message)
        assertThat(messages).containsNoDuplicates()
        messages.forEach { assertThat(it).isNotEmpty() }
    }

    @Test
    fun `length hints format the entered length`() {
        assertThat(OnboardingText.driveHintTakesLength(DriveIdValidation.TooShort)).isTrue()
        assertThat(context.getString(OnboardingText.driveHint(DriveIdValidation.TooShort), 10))
            .isEqualTo("Too short: file IDs have at least 25 characters (10 entered).")
        assertThat(OnboardingText.driveHintTakesLength(DriveIdValidation.Valid)).isFalse()
    }

    @Test
    fun `xtream explanation reflects availability and choice`() {
        val notXtream = OnboardingUiState(sourceType = SourceType.M3uUrl, xtreamAvailability = XtreamAvailability.NotXtreamShaped)
        assertThat(context.getString(OnboardingText.xtreamExplanation(notXtream))).startsWith("Unavailable")
        val off = OnboardingUiState(xtreamRequested = false)
        assertThat(context.getString(OnboardingText.xtreamExplanation(off))).startsWith("Off")
        val drive = OnboardingUiState()
        assertThat(context.getString(OnboardingText.xtreamExplanation(drive))).contains("turned off automatically")
    }
}
