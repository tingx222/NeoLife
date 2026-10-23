"""Add course invariants to existing local tables without importing the destructive seed SQL."""
import os
import pathlib
import re
import subprocess
import tempfile

config = (pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/application.yaml').read_text(encoding='utf-8-sig')
datasource = config.split('  datasource:', 1)[1].split('  redis:', 1)[0]
def setting(name):
    return re.search(r'^    ' + name + r':\s*(.+)$', datasource, re.M).group(1).strip()
url = os.getenv('SPRING_DATASOURCE_URL', setting('url'))
match = re.match(r'jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)', url)
if not match:
    raise SystemExit('Unsupported local JDBC URL')
host, port, database = match.groups()
username = os.getenv('SPRING_DATASOURCE_USERNAME', setting('username'))
password = os.getenv('SPRING_DATASOURCE_PASSWORD', setting('password'))
def quoted(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
with tempfile.TemporaryDirectory(prefix='hmdp-migration-') as temporary:
    defaults = pathlib.Path(temporary) / 'client.cnf'
    defaults.write_text('[client]\nhost=' + quoted(host) + '\nport=' + (port or '3306')
                        + '\nuser=' + quoted(username) + '\npassword=' + quoted(password) + '\n', encoding='utf-8')
    def sql(statement):
        result = subprocess.run(['mysql', '--defaults-extra-file=' + str(defaults), '-N', '-B', database, '-e', statement],
                                capture_output=True, text=True, check=True)
        return result.stdout.strip()
    targets = [('tb_voucher_order', 'user_id,voucher_id', 'uk_user_voucher'),
               ('tb_follow', 'user_id,follow_user_id', 'uk_user_follow')]
    # Validate all tables before applying any DDL; do not delete duplicate business data.
    for table, columns, index in targets:
        duplicate = sql(f'SELECT COUNT(*) FROM (SELECT {columns} FROM {table} GROUP BY {columns} HAVING COUNT(*)>1) duplicates')
        if duplicate != '0':
            raise SystemExit(f'{table} has {duplicate} duplicate groups; resolve manually, no DDL applied')
    for table, columns, index in targets:
        present = sql(f"SELECT COUNT(*) FROM (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) cols FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND non_unique=0 GROUP BY index_name) indexes WHERE cols='{columns}'")
        if present == '0':
            sql(f'ALTER TABLE {table} ADD UNIQUE KEY {index} ({columns})')
            print(f'Added {table}.{index}')
        else:
            print(f'{table}: equivalent unique index already exists')
