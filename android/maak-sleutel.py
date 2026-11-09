# Maakt de vaste ondertekening voor de Android-app, zet die als geheim op GitHub en start de bouw.
# Eenmalig. Schrijft niets naar schijf en laat de sleutel niet zien. Nodig: Python met "cryptography" en GitHub CLI (ingelogd).
import base64
import datetime
import secrets
import subprocess

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

GH = r"C:\Program Files\GitHub CLI\gh.exe"
REPO = "nbroers05/dashboard-app"

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Nog reageren")])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (
    x509.CertificateBuilder()
    .subject_name(name)
    .issuer_name(name)
    .public_key(key.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(now - datetime.timedelta(days=1))
    .not_valid_after(now + datetime.timedelta(days=365 * 30))
    .sign(key, hashes.SHA256())
)
password = secrets.token_urlsafe(32)
p12 = pkcs12.serialize_key_and_certificates(b"reageren", key, cert, None, serialization.BestAvailableEncryption(password.encode()))


def put(secret, value):
    subprocess.run([GH, "secret", "set", secret, "--repo", REPO], input=value.encode(), check=True)


put("ANDROID_KEYSTORE_B64", base64.b64encode(p12).decode())
put("ANDROID_KEYSTORE_PASSWORD", password)
subprocess.run([GH, "workflow", "run", "android.yml", "--repo", REPO], check=True)
print("Klaar. De bouw is gestart; over een paar minuten staat de app online.")
