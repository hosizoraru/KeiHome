package os.kei.hyper.home.xposed

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackBudgets

class NativeIconImportStorageTest {
  @Test fun rejectsTheObservedIosLowStorageCaseBeforeSubmission() {
    val error = assertThrows(IOException::class.java) {
      NativeIconImportStorage.checkAvailable(654935850, 1536L * 1024 * 1024)
    }
    assertTrue(error.message!!.contains("Insufficient storage"))
    assertTrue(error.message!!.contains("Free storage and retry"))
  }

  @Test fun acceptsTheReservationBoundaryAndRejectsOneByteLess() {
    val required = NativeIconImportStorage.requiredFreeBytes(654935850)
    NativeIconImportStorage.checkAvailable(654935850, required)
    assertThrows(IOException::class.java) {
      NativeIconImportStorage.checkAvailable(654935850, required - 1)
    }
  }

  @Test fun maximumArchiveDoesNotOverflowAndInvalidSizesAreRejected() {
    assertTrue(NativeIconImportStorage.requiredFreeBytes(IconPackBudgets.ARCHIVE_BYTES) > 3L * 1024 * 1024 * 1024)
    for (invalid in listOf(-1L, 0L, IconPackBudgets.ARCHIVE_BYTES + 1)) {
      assertThrows(IllegalArgumentException::class.java) {
        NativeIconImportStorage.requiredFreeBytes(invalid)
      }
    }
  }
}
