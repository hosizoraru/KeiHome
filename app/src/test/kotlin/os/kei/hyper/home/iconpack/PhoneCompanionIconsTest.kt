package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class PhoneCompanionIconsTest {
  private val source = "com.android.incallui"
  private val targets = listOf("com.android.server.telecom", "com.android.phone")
  private val day = art("incall_day")
  private val night = art("incall_night")

  @Test fun enabledByDefaultCompletesBothMissingTargetsFromExactInCallPackage() {
    val donor = componentEntry(source)
    val icons = PhoneCompanionIcons(listOf(donor))
    assertTrue(icons.authoredTargets.isEmpty())
    assertEquals(targets.map(::IconPackComponent), icons.completionTargets())
    for (target in targets) {
      assertEquals(donor, icons.donor(IconPackComponent(target), null))
      assertEquals(donor, icons.donor(IconPackComponent(target, "os.foreign.AnyActivity"), null))
    }
  }

  @Test fun disabledMappingHasNoDonorOrCompletionTargetsAndCanBeReEnabled() {
    val donor = componentEntry(source)
    for (enabled in listOf(true, false, true)) {
      val icons = PhoneCompanionIcons(listOf(donor), enabled)
      assertEquals(if (enabled) targets.map(::IconPackComponent) else emptyList(), icons.completionTargets())
      targets.forEach { target ->
        assertEquals(if (enabled) donor else null, icons.donor(IconPackComponent(target), null))
      }
    }
  }

  @Test fun absentOrArtworkFreeInCallEntriesNeverCreateCompletionTargets() {
    for (entries in listOf(emptyList(), listOf(componentEntry("os.other.app")),
      listOf(componentEntry(source).copy(light = null, dark = null)),
      listOf(nativeEntry(source).copy(light = null, dark = null)))) {
      val icons = PhoneCompanionIcons(entries)
      assertTrue(icons.completionTargets().isEmpty())
      targets.forEach { assertNull(icons.donor(IconPackComponent(it), null)) }
    }
  }

  @Test fun nearPrefixPackagesAndDottedNativeNamesCannotMasqueradeAsInCallDonors() {
    for (name in listOf("$source.beta", "${source}backup", "${source}_beta", "$source.Main")) {
      for (entry in listOf(componentEntry(name), nativeEntry(name))) {
        val icons = PhoneCompanionIcons(listOf(entry))
        assertTrue("Rejected source: $name", icons.completionTargets().isEmpty())
        targets.forEach { assertNull(icons.donor(IconPackComponent(it), null)) }
      }
    }
  }

  @Test fun sharingIsOneWayAndDoesNotExpandToDialersOrSimilarTargetPackages() {
    val ownTargets = targets.map { componentEntry(it) }
    val reverse = PhoneCompanionIcons(ownTargets)
    assertEquals(targets.toSet(), reverse.authoredTargets)
    assertTrue(reverse.completionTargets().isEmpty())
    assertNull(reverse.donor(IconPackComponent(source), null))
    val forward = PhoneCompanionIcons(listOf(componentEntry(source)))
    for (pkg in listOf(source, "com.android.dialer", "com.google.android.dialer",
      "com.android.phone.beta", "com.android.server.telecom.extra", "os.other.app")) {
      assertNull("Unexpected target: $pkg", forward.donor(IconPackComponent(pkg), null))
    }
  }

  @Test fun eachAuthoredTargetIndependentlyBlocksBorrowingForItsWholePackage() {
    val donor = componentEntry(source)
    for (target in targets) {
      val other = targets.single { it != target }
      val authoredEntries = listOf(
        componentEntry(target),
        componentEntry(target, "os.foreign.UnrelatedActivity").copy(dark = null),
        componentEntry(target, "$target.SecondaryActivity").copy(light = null),
        componentEntry(target).copy(light = null, dark = null),
        nativeEntry(target).copy(dark = null),
        nativeEntry("$target.AnyActivity").copy(dark = null),
        nativeEntry("$target#os.foreign.AnyActivity").copy(dark = null),
      )
      for (own in authoredEntries) {
        val entries = listOf(donor, own)
        val icons = PhoneCompanionIcons(entries)
        assertEquals("Authored entry: ${own.key}", setOf(target), icons.authoredTargets)
        assertEquals(listOf(IconPackComponent(other)), icons.completionTargets())
        assertNull(icons.donor(IconPackComponent(target), null))
        assertNull(icons.donor(IconPackComponent(target, "os.foreign.OtherActivity"), null))
        assertEquals(donor, icons.donor(IconPackComponent(other), null))
        assertSame(own, entries[1])
      }
    }
  }

  @Test fun twoAuthoredTargetsKeepTheirOwnLightOnlyAndNativeArtwork() {
    val phone = componentEntry(targets[1], "os.foreign.PhoneSettings").copy(light = art("own_phone"), dark = null)
    val telecom = nativeEntry("${targets[0]}#os.foreign.TelecomSettings")
      .copy(light = art("own_telecom"), dark = null)
    val entries = listOf(componentEntry(source), phone, telecom)
    for (enabled in listOf(false, true)) {
      val icons = PhoneCompanionIcons(entries, enabled)
      assertEquals(targets.toSet(), icons.authoredTargets)
      assertTrue(icons.completionTargets().isEmpty())
      targets.forEach { assertNull(icons.donor(IconPackComponent(it), null)) }
    }
    assertEquals(art("own_phone"), phone.light)
    assertNull(phone.dark)
    assertEquals(art("own_telecom"), telecom.light)
    assertNull(telecom.dark)
  }

  @Test fun explicitExistingTargetsBlockBorrowingEvenOutsideTheAuthoredEntryList() {
    val icons = PhoneCompanionIcons(listOf(componentEntry(source)))
    for (target in targets) {
      for (existing in listOf(
        componentEntry(target).copy(dark = null),
        componentEntry(target, "os.foreign.CustomIcon").copy(light = null),
        nativeEntry(target),
        componentEntry(target).copy(light = null, dark = null),
      )) {
        assertNull(icons.donor(IconPackComponent(target), existing))
      }
    }
  }

  @Test fun packageDonorTakesPriorityOverConflictingActivityDonorsRegardlessOfEntryOrder() {
    val activityA = componentEntry(source, "$source.First", key = "a")
      .copy(light = art("first_day"), dark = art("first_night"))
    val activityB = nativeEntry("$source#os.foreign.Second", key = "b")
      .copy(light = art("second_day"), dark = art("second_night"))
    for (packageDonor in listOf(componentEntry(source, key = "z"), nativeEntry(source, key = "z"))) {
      for (entries in listOf(listOf(activityA, activityB, packageDonor), listOf(packageDonor, activityB, activityA))) {
        val icons = PhoneCompanionIcons(entries)
        assertEquals(targets.map(::IconPackComponent), icons.completionTargets())
        targets.forEach { assertEquals(packageDonor, icons.donor(IconPackComponent(it), null)) }
      }
    }
  }

  @Test fun activityOnlyDonorsAreUsableWhenAllLightAndDarkResourcesAgree() {
    val activityA = componentEntry(source, "os.foreign.First", key = "a")
    val activityB = componentEntry(source, "$source.Second", key = "b")
    val activityC = nativeEntry("$source#os.foreign.Third", key = "c")
    for (entries in listOf(listOf(activityA), listOf(activityA, activityB, activityC),
      listOf(activityC, activityB, activityA))) {
      val icons = PhoneCompanionIcons(entries)
      assertEquals(targets.map(::IconPackComponent), icons.completionTargets())
      for (target in targets) {
        val donor = requireNotNull(icons.donor(IconPackComponent(target), null))
        assertEquals(day, donor.artwork(false))
        assertEquals(night, donor.artwork(true))
      }
    }
  }

  @Test fun conflictingActivityArtworkIsAmbiguousEvenIfOnlyOneModeDiffers() {
    val first = componentEntry(source, "$source.First")
    val conflicts = listOf(
      componentEntry(source, "$source.Second").copy(light = art("different_day")),
      nativeEntry("$source#os.foreign.Second").copy(dark = art("different_night")),
      componentEntry(source, "$source.Third").copy(dark = null),
    )
    for (conflict in conflicts) {
      for (entries in listOf(listOf(first, conflict), listOf(conflict, first))) {
        val icons = PhoneCompanionIcons(entries)
        assertTrue(icons.completionTargets().isEmpty())
        targets.forEach { assertNull(icons.donor(IconPackComponent(it), null)) }
      }
    }
  }

  @Test fun lightOnlyPackageNativeAndActivityDonorsReuseTheSameArtworkInBothModes() {
    val candidates = listOf(
      componentEntry(source),
      nativeEntry(source),
      componentEntry(source, "$source.AnyActivity"),
      nativeEntry("$source#os.foreign.AnyActivity"),
    ).map { it.copy(dark = null) }
    for (entry in candidates) {
      val icons = PhoneCompanionIcons(listOf(entry))
      assertEquals(targets.map(::IconPackComponent), icons.completionTargets())
      for (target in targets) {
        val donor = requireNotNull(icons.donor(IconPackComponent(target), null))
        assertEquals(day, donor.artwork(false))
        assertEquals(day, donor.artwork(true))
      }
      assertNull(entry.dark)
    }
    val agreeingActivities = PhoneCompanionIcons(listOf(candidates[2], candidates[3]))
    targets.forEach {
      val donor = requireNotNull(agreeingActivities.donor(IconPackComponent(it), null))
      assertEquals(day, donor.light)
      assertEquals(day, donor.dark)
    }
  }

  private fun componentEntry(pkg: String, activity: String? = null, key: String = "$pkg/$activity") =
    IconPackEntry(key, IconPackComponent(pkg, activity), light = day, dark = night)

  private fun nativeEntry(name: String, key: String = name) =
    IconPackEntry(key, nativeName = name, light = day, dark = night)

  private fun art(name: String) = IconPackArtwork.Resource(IconDrawableName(name, "drawable"))
}
