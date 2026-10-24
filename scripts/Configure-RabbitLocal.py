"""Prepare ignored local Rabbit credentials without printing or overwriting the DeepSeek key."""
from pathlib import Path
import secrets
import re

root = Path(__file__).resolve().parents[1]
env_file = root / '.env'
env = env_file.read_text(encoding='utf-8-sig') if env_file.exists() else ''
match = re.search(r'^RABBIT_PASSWORD=(.+)$', env, re.M)
password = match.group(1).strip() if match else secrets.token_urlsafe(24)
if not password or password == 'replace-with-your-own-password':
    password = secrets.token_urlsafe(24)
line = 'RABBIT_PASSWORD=' + password
env = re.sub(r'^RABBIT_PASSWORD=.*$', lambda _: line, env, flags=re.M) if match else env + '\n' + line + '\n'
env_file.write_text(env, encoding='utf-8')
config = root / 'config/application.properties'
config.parent.mkdir(exist_ok=True)
text = config.read_text(encoding='utf-8-sig') if config.exists() else ''
settings = {'hmdp.rabbit.enabled': 'true', 'spring.rabbitmq.host': '127.0.0.1', 'spring.rabbitmq.port': '5672',
            'spring.rabbitmq.username': 'hmdp', 'spring.rabbitmq.password': password, 'spring.rabbitmq.virtual-host': 'hmdp'}
for name, value in settings.items():
    expression = '^' + re.escape(name) + r'\s*=.*$'
    text = re.sub(expression, lambda _: name + '=' + value, text, flags=re.M) if re.search(expression, text, re.M) else text + '\n' + name + '=' + value
config.write_text(text + '\n', encoding='utf-8')
profile = root / 'config/application-rabbit.properties'
profile.write_text('# Local rabbit profile credentials; ignored by Git.\n' +
                   '\n'.join(name + '=' + value for name, value in settings.items()) + '\n', encoding='utf-8')
print('Ignored local .env, application.properties and application-rabbit.properties configured; no credentials printed.')
