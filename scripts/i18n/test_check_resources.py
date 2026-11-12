"""Small temporary Android catalogs: no SDK, network or device required."""

from contextlib import redirect_stdout
import importlib.util
from io import StringIO
from pathlib import Path
import sys
import tempfile
import unittest

import check_resources as checker


class FormatterTest(unittest.TestCase):
    def test_positional_reordering_and_repetition(self):
        self.assertEqual(checker.signature("%1$s: %2$d"),
                         checker.signature("%2$d / %1$s / %1$s"))

    def test_implicit_and_relative_arguments(self):
        self.assertEqual(checker.signature("%s %d"), checker.signature("%1$s %2$d"))
        self.assertEqual(checker.signature("%1$s %<s"), {1: {"s"}})
        with self.assertRaises(ValueError):
            checker.signature("%<s")

    def test_conversion_types_and_indexes(self):
        self.assertNotEqual(checker.signature("%1$d"), checker.signature("%1$s"))
        self.assertNotEqual(checker.signature("%1$s"), checker.signature("%2$s"))
        self.assertEqual(checker.signature("%1$04x"), checker.signature("%1$d"))
        self.assertEqual(checker.signature("%1$.2f"), checker.signature("%1$e"))
        self.assertEqual(checker.signature("%1$tY"), checker.signature("%1$tm"))
        with self.assertRaises(ValueError):
            checker.signature("%0$s")

    def test_literals_and_disabled_formatting(self):
        self.assertEqual(checker.signature("100%"), {})
        self.assertEqual(checker.signature("100%% %n"), {})
        self.assertEqual(checker.signature("%1$s 50%", formatted=False), {})
        self.assertEqual(checker.signature("@string/other"), {})
        with self.assertRaises(ValueError):
            checker.signature("%1$s 50%")
        with self.assertRaises(ValueError):
            checker.signature("%1$q")


class ResourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.res = Path(self.temp.name) / "res"
        self.write("xml/locales_config.xml", """<locale-config
            xmlns:android="http://schemas.android.com/apk/res/android">
            <locale android:name="en"/><locale android:name="zh-CN"/>
            <locale android:name="ja"/></locale-config>""")

    def write(self, name, xml):
        path = self.res / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(xml, encoding="utf-8")
        return path

    def catalogs(self, text):
        for folder in ("values", "values-zh-rCN", "values-ja"):
            self.write(folder + "/strings.xml", "<resources>" + text + "</resources>")

    def findings(self, *required):
        return checker.audit(self.res, required)[0]

    def errors(self):
        return [item for item in self.findings() if item.level == "ERROR"]

    def test_complete_catalog_and_nontranslatable_fallback(self):
        self.catalogs('<string name="message">Hello %1$s</string>')
        self.write("values/fixed.xml", '<resources><string name="brand" '
                   'translatable="false">Brand</string></resources>')
        self.assertEqual(self.findings(), [])

    def test_missing_published_is_fatal_with_base_location(self):
        self.catalogs('<string name="message">Hello</string>')
        self.write("values-ja/strings.xml", "<resources/>")
        errors = self.errors()
        self.assertEqual(len(errors), 1)
        self.assertIn("ja: missing string/message", str(errors[0]))
        self.assertIn("values/strings.xml:1", errors[0].location)

    def test_partial_new_locale_is_warning_and_can_be_required(self):
        self.catalogs('<string name="message">Hello</string>')
        self.write("values-fr/strings.xml", "<resources/>")
        self.assertEqual(self.errors(), [])
        self.assertTrue(any("fr: missing" in str(item) for item in self.findings()))
        self.assertTrue(any(item.level == "ERROR" and "fr: missing" in str(item)
                            for item in self.findings("fr")))
        self.assertTrue(any("de: missing" in str(item) for item in self.findings("de")))

    def test_registered_new_locale_is_still_allowed_partial(self):
        self.catalogs('<string name="message">Hello</string>')
        path = self.res / "xml/locales_config.xml"
        path.write_text(path.read_text().replace("</locale-config>",
                        '<locale android:name="fr"/></locale-config>'))
        self.assertEqual(self.errors(), [])
        self.assertTrue(any("fr: missing" in str(item) for item in self.findings()))

    def test_locale_configuration_and_sparse_variants(self):
        self.assertEqual(checker.locale_configuration("values-night"), (None, ("night",)))
        self.assertEqual(checker.locale_configuration("values-mcc310-b+zh+Hans+CN-night"),
                         ("zh-Hans-CN", ("mcc310", "night")))
        self.assertEqual(checker.locale_configuration("values-zh-rCN-v31"),
                         ("zh-CN", ("v31",)))
        self.catalogs('<string name="message">Hello</string><string name="more">More</string>')
        self.write("values-ja-night/strings.xml",
                   '<resources><string name="message">Night</string></resources>')
        self.write("values-en-rGB/strings.xml", "<resources/>")
        self.assertEqual(self.errors(), [])

    def test_regional_catalog_uses_language_fallback(self):
        self.catalogs('<string name="message">Hello</string>')
        self.write("values-fr/strings.xml",
                   '<resources><string name="message">Bonjour</string></resources>')
        self.write("values-fr-rCA/strings.xml", "<resources/>")
        self.assertFalse(any("missing" in str(item) for item in self.findings("fr-CA")))

    def test_duplicates_across_files_but_not_qualifiers(self):
        self.catalogs('<string name="message">Hello</string>')
        self.write("values/second.xml",
                   '<resources>\n<string name="message">Again</string></resources>')
        self.write("values-night/strings.xml",
                   '<resources><string name="message">Night</string></resources>')
        errors = self.errors()
        self.assertEqual(len(errors), 1)
        self.assertIn("second.xml:2", str(errors[0]))
        self.assertIn("first at", str(errors[0]))

    def test_xml_outside_values_is_validated(self):
        self.catalogs('<string name="message">Hello</string>')
        self.write("drawable/bad.xml", "<shape>\n</broken>")
        self.assertIn("drawable/bad.xml:2", str(self.errors()[0]))

    def test_registration_errors(self):
        self.catalogs('<string name="message">Hello</string>')
        self.write("xml/locales_config.xml", """<locale-config
            xmlns:android="http://schemas.android.com/apk/res/android">
            <locale android:name="en"/><locale android:name="en"/></locale-config>""")
        errors = self.errors()
        self.assertTrue(any("duplicate registered locale en" in str(item) for item in errors))
        self.assertTrue(any("published locale ja" in str(item) for item in errors))

    def test_unknown_translation_key(self):
        self.catalogs('<string name="message">Hello</string>')
        self.write("values-ja/extra.xml",
                   '<resources><string name="typo">Extra</string></resources>')
        self.assertIn("no base definition", str(self.errors()[0]))

    def test_markup_and_reordered_formatters(self):
        self.catalogs('<string name="message">%1$s: %2$d</string>')
        self.write("values-ja/strings.xml", """<resources
            xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
            <string name="message"><b>%2$d</b> /
            <xliff:g id="name">%1$s</xliff:g></string></resources>""")
        self.assertEqual(self.errors(), [])

    def test_wrong_formatter_reports_translated_and_original_location(self):
        self.catalogs('<string name="message">%1$s: %2$d</string>')
        self.write("values-ja/strings.xml",
                   '<resources>\n<string name="message">%1$s: %2$s</string></resources>')
        error = str(self.errors()[0])
        self.assertIn("values-ja/strings.xml:2", error)
        self.assertIn("values/strings.xml:1", error)
        self.assertIn("formatter", error)

    def test_formatted_false_is_literal(self):
        self.catalogs('<string name="example" formatted="false">%1$s 50%</string>')
        self.assertEqual(self.errors(), [])

    def test_literal_percent_and_android_apostrophe_quoting(self):
        self.catalogs(r"""<string name="message">It\'s %1$d%% complete</string>""")
        self.write("values-ja/strings.xml",
                   """<resources><string name="message">"It's %1$d%% complete"</string>"""
                   "</resources>")
        self.assertEqual(self.findings(), [])

    def test_translation_cannot_disable_a_real_formatter_contract(self):
        self.catalogs('<string name="message">%1$s</string>')
        self.write("values-ja/strings.xml", '<resources><string name="message" '
                   'formatted="false">Missing argument</string></resources>')
        self.assertIn("formatter", str(self.errors()[0]))

    def test_item_string_definition_has_the_same_resource_identity(self):
        self.catalogs('<item type="string" name="message">%1$s</item>')
        self.write("values-ja/strings.xml",
                   '<resources><string name="message">%1$s</string></resources>')
        self.assertEqual(self.findings(), [])

    def test_string_references_use_translated_target(self):
        self.catalogs('<string name="target">%1$d</string>'
                      '<string name="message">@string/target</string>')
        self.assertEqual(self.errors(), [])
        self.write("values-ja/strings.xml", '<resources><string name="target">%1$s</string>'
                   '<string name="message">@string/target</string></resources>')
        self.assertEqual(len(self.errors()), 2)

    def test_reference_cycle_is_reported(self):
        self.catalogs('<string name="message">@string/message</string>')
        self.assertTrue(any("cyclic" in str(item) for item in self.errors()))

    def test_arrays_and_locale_specific_plurals(self):
        self.catalogs('<string-array name="menu"><item>%1$s</item><item>Other</item>'
                      '</string-array><plurals name="count"><item quantity="one">%d item</item>'
                      '<item quantity="other">%d items</item></plurals>')
        self.write("values-ja/strings.xml", '<resources><string-array name="menu">'
                   '<item>%1$s</item><item>Other</item></string-array><plurals name="count">'
                   '<item quantity="zero">None</item>'
                   '<item quantity="other">%1$d items</item></plurals></resources>')
        self.assertEqual(self.findings(), [])

    def test_bad_array_and_plural_placeholder_types(self):
        self.catalogs('<string-array name="menu"><item>%1$s</item></string-array>'
                      '<plurals name="count"><item quantity="other">%d items</item></plurals>')
        self.write("values-ja/strings.xml", '<resources><string-array name="menu">'
                   '<item>%1$d</item></string-array><plurals name="count">'
                   '<item quantity="other">%1$s items</item></plurals></resources>')
        self.assertEqual(len(self.errors()), 2)

    def test_plural_structure_and_array_length_review(self):
        self.catalogs('<string-array name="menu"><item>A</item></string-array>')
        self.write("values-ja/strings.xml", '<resources><string-array name="menu">'
                   '<item>A</item><item>B</item></string-array></resources>')
        self.assertEqual(self.errors(), [])
        self.assertTrue(any("array length" in str(item) for item in self.findings()))
        self.write("values/bad.xml", '<resources><plurals name="bad">'
                   '<item quantity="one">A</item><item quantity="one">B</item>'
                   '<item quantity="bogus">C</item></plurals></resources>')
        messages = "\n".join(map(str, self.errors()))
        self.assertIn("duplicate plural quantity", messages)
        self.assertIn('require quantity="other"', messages)
        self.assertIn("invalid plural quantity", messages)

    def test_cli_exit_codes_and_check_group(self):
        self.catalogs('<string name="message">Hello</string>')
        with redirect_stdout(StringIO()):
            self.assertEqual(checker.main(["--res-dir", str(self.res)]), 0)
            self.assertEqual(checker.main(["--res-dir", str(self.res),
                                          "--require-complete", "fr"]), 1)
        spec = importlib.util.spec_from_file_location(
            "checks", Path(__file__).resolve().parents[1] / "checks.py")
        checks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checks)
        self.assertEqual(checks.command_for("i18n", None, None),
                         [sys.executable, "scripts/i18n/check_resources.py"])
        with self.assertRaises(ValueError):
            checks.command_for("i18n", None, "method")


if __name__ == "__main__":
    unittest.main()
