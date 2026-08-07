package moe.tekuza.m9player

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AudiobookUiSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, BookReaderActivity::class.java).apply {
                putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, "吾輩は猫である")
                putExtra(BookReaderActivity.EXTRA_UI_TEST_MODE, true)
                putExtra(BookReaderActivity.EXTRA_UI_LAYOUT_EDIT_MODE, true)
            }
        )
        finish()
    }
}
