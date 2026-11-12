package os.kei.hyper.home.iconpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IconBuildInventoryTest {
  @Test fun moreThanSixteenThousandNamesSurviveTheSnapshotMetadataRoundTrip() {
    val names = (0..20_000).mapTo(linkedSetOf()) { "com.sample.icon$it" }
    assertEquals(names, IconBuildInventory.decodeNames(IconBuildInventory.encodeNames(names)))
  }

  @Test fun producerAndConsumerRejectInvalidOrNoncanonicalNames() {
    assertThrows(IllegalArgumentException::class.java) {
      IconBuildInventory.encodeNames(setOf("../outside"))
    }
    for (raw in listOf("com.sample.b\ncom.sample.a\n", "com.sample.a\ncom.sample.a\n",
      "com.sample.a", "../outside\n")) {
      assertThrows(IllegalArgumentException::class.java) {
        IconBuildInventory.decodeNames(raw.toByteArray())
      }
    }
    assertEquals(emptySet<String>(), IconBuildInventory.decodeNames(IconBuildInventory.encodeNames(emptySet())))
  }

  @Test fun producersCannotPublishAnInventoryBeyondTheSharedEntryBound() {
    val names = (0..IconPackLimits().maxEntries).mapTo(hashSetOf()) { "com.sample.icon$it" }
    assertThrows(IllegalArgumentException::class.java) { IconBuildInventory.encodeNames(names) }
    assertThrows(IllegalArgumentException::class.java) {
      IconBuildInventory.decodeNames(ByteArray(IconBuildInventory.MAX_BYTES + 1))
    }
  }


  @Test fun qualifiedNativeNamesRoundTripButUnsafeSeparatorsNeverEnterInventory() {
    val qualified = "com.example.debug#com.example.Main"
    val names = setOf("com.example", qualified)
    assertEquals(names, IconBuildInventory.decodeNames(IconBuildInventory.encodeNames(names)))
    for (invalid in listOf("com.example#../Main", "com.example#com.other#Main", "#com.example.Main", "com.example#")) {
      assertThrows(IllegalArgumentException::class.java) { IconBuildInventory.encodeNames(setOf(invalid)) }
      assertThrows(IllegalArgumentException::class.java) { IconBuildInventory.decodeNames("$invalid\n".toByteArray()) }
    }
  }
}
