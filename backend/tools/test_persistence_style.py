"""Regression fixtures for the production persistence policy, not SQL business semantics."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('persistence_style', Path(__file__).with_name('check-persistence-style.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PersistenceStyleTest(unittest.TestCase):
    def test_rejects_executors_and_sql_annotations(self):
        for source in ['new JdbcTemplate(ds)', 'NamedParameterJdbcTemplate jdbc;',
                       'JdbcClient.create(ds)', 'connection.prepareStatement(value)',
                       '@Select("lookup")', '@org.apache.ibatis.annotations.SelectProvider(type=X.class)',
                       'new org.apache.ibatis.jdbc.SQL()']:
            with self.subTest(source=source):
                self.assertTrue(gate.violations(source))

    def test_rejects_sql_literals_text_blocks_and_concatenation(self):
        for source in ['"SELECT id FROM t"', '"""\nUPDATE t SET x=1\n"""',
                       '"INSERT INTO t VALUES (?)"', '"DELETE FROM t"',
                       '"SET SESSION time_zone=1"', '"SEL" + "ECT id FROM t"']:
            with self.subTest(source=source):
                self.assertTrue(gate.violations(source))

    def test_allows_comments_transactions_metadata_and_mapper_calls(self):
        source = '''// JdbcTemplate SELECT ignored in historical comments
        /* UPDATE t SET x=1 */
        new DataSourceTransactionManager(source);
        source.getConnection().getCatalog();
        mapper.selectById(id);
        String message = "current facts unavailable";
        '''
        self.assertEqual([], gate.violations(source))

    def test_scans_production_only_and_rejects_xml_raw_substitution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prod = root / 'pet-sample-biz/src/main/java/Sample.java'
            prod.parent.mkdir(parents=True)
            prod.write_text('class Sample {}', encoding='utf-8')
            test = root / 'pet-sample-biz/src/test/java/SampleTest.java'
            test.parent.mkdir(parents=True)
            test.write_text('new JdbcTemplate(ds);', encoding='utf-8')
            xml = root / 'pet-sample-biz/src/main/resources/mapper/Sample.xml'
            xml.parent.mkdir(parents=True)
            xml.write_text('<select>SELECT id FROM t WHERE id=#{id}</select>', encoding='utf-8')
            self.assertEqual([], gate.check(root))
            xml.write_text('<select>${sql}</select>', encoding='utf-8')
            self.assertTrue(gate.check(root))

    def test_empty_scan_cannot_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertTrue(gate.check(tmp))
