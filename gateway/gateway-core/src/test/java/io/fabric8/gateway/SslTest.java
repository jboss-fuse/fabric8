/**
 *  Copyright 2005-2019 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.gateway;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Enumeration;
import java.util.Map;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class SslTest {

    public static Logger LOG = LoggerFactory.getLogger(SslTest.class);
    public static DateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static {
        DATE.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Test
    public void providers() throws Exception {
        Provider[] providers = Security.getProviders();
        for (Provider p : providers) {
            LOG.info("Provider " + p);
            for (Map.Entry<Object, Object> entry : p.entrySet()) {
                LOG.info("   " + entry.getKey() + ": " + entry.getValue());
            }
        }
    }

    @Test
    public void certificates() throws Exception {
        // sun.security.rsa.RSAKeyPairGenerator
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "SunJSSE");
//        assertThat(getField(kpg, "spi").getClass().getName(), equalTo("sun.security.rsa.RSAKeyPairGenerator"));

        // 3 key pairs

        // `openssl genrsa -out ca1.key`
        // $ openssl asn1parse -in ca1.key -i
        //     0:d=0  hl=4 l=1187 cons: SEQUENCE
        //     4:d=1  hl=2 l=   1 prim:  INTEGER           :00
        //     7:d=1  hl=4 l= 257 prim:  INTEGER           :DF93B4A74247...
        //   268:d=1  hl=2 l=   3 prim:  INTEGER           :010001
        //   273:d=1  hl=4 l= 256 prim:  INTEGER           :5A8C09880BD1...
        //   533:d=1  hl=3 l= 129 prim:  INTEGER           :F540222F8DDC...
        //   665:d=1  hl=3 l= 129 prim:  INTEGER           :E9606196298B...
        //   797:d=1  hl=3 l= 128 prim:  INTEGER           :1C3748B797E6...
        //   928:d=1  hl=3 l= 129 prim:  INTEGER           :D8D5F055F12B...
        //  1060:d=1  hl=3 l= 128 prim:  INTEGER           :165E65231AFE...
        //
        // $ openssl asn1parse -in ca1-public.key -i
        //     0:d=0  hl=4 l= 290 cons: SEQUENCE
        //     4:d=1  hl=2 l=  13 cons:  SEQUENCE
        //     6:d=2  hl=2 l=   9 prim:   OBJECT            :rsaEncryption
        //    17:d=2  hl=2 l=   0 prim:   NULL
        //    19:d=1  hl=4 l= 271 prim:  BIT STRING
        //
        // $ openssl asn1parse -in ca1-public.key -i -strparse 19
        //     0:d=0  hl=4 l= 266 cons: SEQUENCE
        //     4:d=1  hl=4 l= 257 prim:  INTEGER           :DF93B4A74247...
        //   265:d=1  hl=2 l=   3 prim:  INTEGER           :010001

        // in Java (RSA/SunJSSE):
        // public key:
        //  - BigInteger n;       // modulus
        //  - BigInteger e;       // public exponent
        // private key:
        //  - BigInteger n;       // modulus
        //  - BigInteger e;       // public exponent
        //  - BigInteger d;       // private exponent
        //  - BigInteger p;       // prime p
        //  - BigInteger q;       // prime q
        //  - BigInteger pe;      // prime exponent p
        //  - BigInteger qe;      // prime exponent q
        //  - BigInteger coeff;   // CRT coeffcient

        // main method to generate key pair
        KeyPair caKeyPair = kpg.generateKeyPair();
        PrivateKey caPrivateKey = caKeyPair.getPrivate(); // sun.security.rsa.RSAPrivateCrtKeyImpl
        PublicKey caPublicKey = caKeyPair.getPublic();    // sun.security.rsa.RSAPublicKeyImpl

        // direct access to data knowing the key hierarchy
        ((RSAPublicKey)caPublicKey).getPublicExponent();
        ((RSAPrivateKey)caPrivateKey).getPrivateExponent();
        ((RSAPrivateCrtKey)caPrivateKey).getPrimeP();
        ((RSAPrivateCrtKey)caPrivateKey).getPrimeQ();

        assertThat(((RSAPrivateKey) caPrivateKey).getModulus(), equalTo(((RSAPublicKey) caPublicKey).getModulus()));

        // key (actual key) to specification (information allowing to recreate the key)
        KeyFactory kf = KeyFactory.getInstance("RSA", "SunJSSE");
//        assertThat(getField(kf, "spi").getClass().getName(), equalTo("sun.security.rsa.RSAKeyFactory"));
        // sun.security.rsa.RSAKeyFactory knows which interfaces/classes it can handle and directly takes what's
        // needed, e.g., java.security.interfaces.RSAPrivateCrtKey.getPrimeExponentP() in translateKey()
        // then, after successful translation of the key, it creates instance of
        // java.security.spec.RSAPrivateCrtKeySpec

        RSAPrivateCrtKeySpec caPrivateKeySpec = kf.getKeySpec(caPrivateKey, RSAPrivateCrtKeySpec.class);
        assertThat(((RSAPrivateKey) caPrivateKey).getModulus(), equalTo(caPrivateKeySpec.getModulus()));

        KeyPair serverKeyPair = kpg.generateKeyPair();
        PrivateKey serverPrivateKey = serverKeyPair.getPrivate();
        PublicKey serverPublicKey = serverKeyPair.getPublic();

        KeyPair clientKeyPair = kpg.generateKeyPair();
        PrivateKey clientPrivateKey = clientKeyPair.getPrivate();
        PublicKey clientPublicKey = clientKeyPair.getPublic();

        LOG.info("CA private key format: " + caPrivateKey.getFormat());
        LOG.info("CA public key format: " + caPublicKey.getFormat());
        Files.write(new File("target/ca-public.key").toPath(), caPublicKey.getEncoded());
        Files.write(new File("target/ca-private.key").toPath(), caPrivateKey.getEncoded());

        Files.write(new File("target/server-private.key").toPath(), serverPrivateKey.getEncoded());
        Files.write(new File("target/client-private.key").toPath(), clientPrivateKey.getEncoded());

        // ASN.1 structure of encoded Java classes:
        // public key:
        // $ openssl asn1parse -inform der -in ca-public-5901758342174098433.key -i
        //     0:d=0  hl=4 l= 290 cons: SEQUENCE
        //     4:d=1  hl=2 l=  13 cons:  SEQUENCE
        //     6:d=2  hl=2 l=   9 prim:   OBJECT            :rsaEncryption
        //    17:d=2  hl=2 l=   0 prim:   NULL
        //    19:d=1  hl=4 l= 271 prim:  BIT STRING
        //
        // $ openssl asn1parse -inform der -in ca-public-5901758342174098433.key -i -strparse 19
        //     0:d=0  hl=4 l= 266 cons: SEQUENCE
        //     4:d=1  hl=4 l= 257 prim:  INTEGER           :88C1DB1E6C12...
        //   265:d=1  hl=2 l=   3 prim:  INTEGER           :010001
        //
        // private key:
        // $ openssl asn1parse -inform der -in ca-private-7968518175395329768.key -i
        //     0:d=0  hl=4 l=1213 cons: SEQUENCE
        //     4:d=1  hl=2 l=   1 prim:  INTEGER           :00
        //     7:d=1  hl=2 l=  13 cons:  SEQUENCE
        //     9:d=2  hl=2 l=   9 prim:   OBJECT            :rsaEncryption
        //    20:d=2  hl=2 l=   0 prim:   NULL
        //    22:d=1  hl=4 l=1191 prim:  OCTET STRING      [HEX DUMP]:308204A302010002820101...
        //
        // $ openssl asn1parse -inform der -in ca-private-7968518175395329768.key -i -strparse 22
        //     0:d=0  hl=4 l=1187 cons: SEQUENCE
        //     4:d=1  hl=2 l=   1 prim:  INTEGER           :00
        //     7:d=1  hl=4 l= 257 prim:  INTEGER           :88C1DB1E6C12...
        //   268:d=1  hl=2 l=   3 prim:  INTEGER           :010001
        //   273:d=1  hl=4 l= 256 prim:  INTEGER           :3960E4B7B1EE...
        //   533:d=1  hl=3 l= 129 prim:  INTEGER           :DCF0F10D7BEE...
        //   665:d=1  hl=3 l= 129 prim:  INTEGER           :9E75333E7D30...
        //   797:d=1  hl=3 l= 129 prim:  INTEGER           :B4C5D715474F...
        //   929:d=1  hl=3 l= 128 prim:  INTEGER           :06C6580489F1...
        //  1060:d=1  hl=3 l= 128 prim:  INTEGER           :54338EFF7906...

        // Certificates - generated using Bouncycastle

        X500Name caName = new X500Name("cn=CA");
        X500Name serverName = new X500Name("cn=server");
        X500Name clientName = new X500Name("cn=client");

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                caName, BigInteger.ZERO,
                DATE.parse("2019-01-01 00:00:00"), DATE.parse("2039-01-01 00:00:00"),
                caName, caPublicKey);

        // extensions - https://tools.ietf.org/html/rfc5280#section-4.2

        // subject key identifier: https://tools.ietf.org/html/rfc5280#section-4.2.1.2
        // it's mandatory for CA certificates. All certificates signed by this CA certificate should have
        // "authority key identifier" matching "subject key identifier" of the signing CA certificate
        // RFC5280 defines two methods of deriving SKI from public key of CA:
        // 1) SHA1(BIT STRING from public key)
        // 2) 0100 + 60 least significant bits of SHA1(BIT STRING from public key)
        ASN1InputStream caPublicKeyASN1InputStream = new ASN1InputStream(caPublicKey.getEncoded());
        ASN1Sequence s = (ASN1Sequence) caPublicKeyASN1InputStream.readObject();
        DERBitString bitString = (DERBitString) s.getObjectAt(1);
        MessageDigest sha1 = MessageDigest.getInstance("SHA1");
        byte[] caKeyId = sha1.digest(bitString.getBytes());
        builder.addExtension(Extension.subjectKeyIdentifier, false, new SubjectKeyIdentifier(caKeyId));

        // authority key identifier: https://tools.ietf.org/html/rfc5280#section-4.2.1.1
        // it's used to identify the public key matching the private key used to sign some certificate.
        // this extension is mandatory except for self-signed certificates. But in this case, if present,
        // it should match "subject key identifier"
        builder.addExtension(Extension.authorityKeyIdentifier, false, new AuthorityKeyIdentifier(caKeyId));

        // basic constraints: https://tools.ietf.org/html/rfc5280#section-4.2.1.9
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        // key usage: https://tools.ietf.org/html/rfc5280#section-4.2.1.3
        // CA self-signed certificate created using `openssl` has:
        // X509v3 Key Usage:
        //     Certificate Sign, CRL Sign
        builder.addExtension(Extension.keyUsage, false, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        // org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
        // openssl by default uses "sha256WithRSAEncryption"
        ContentSigner caContentSigner = new JcaContentSignerBuilder("SHA1WITHRSA").build(caPrivateKey);

        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();

        X509Certificate caX509Certificate = converter.getCertificate(builder.build(caContentSigner));
        Files.write(new File("target/ca.cer").toPath(), caX509Certificate.getEncoded());

        // in simplest form (no extensions), it's simply:
        // $ openssl x509 -inform der -in ca.cer -noout -text
        // Certificate:
        //     Data:
        //         Version: 3 (0x2)
        //         Serial Number: 0 (0x0)
        //         Signature Algorithm: sha1WithRSAEncryption
        //         Issuer: CN = CA
        //         Validity
        //             Not Before: Jan  1 00:00:00 2019 GMT
        //             Not After : Jan  1 00:00:00 2039 GMT
        //         Subject: CN = CA
        //         Subject Public Key Info:
        //             Public Key Algorithm: rsaEncryption
        //                 RSA Public-Key: (2048 bit)
        //                 Modulus:
        //                     00:c4:78:dd:84:a1:6f:8f:5b:e2:4d:8e:a7:3e:11:
        //                     c4:22:34:ad:90:89:65:82:c2:be:a6:73:9c:db:1d:
        // ...

        // server and client certificates

        JcaX509v3CertificateBuilder serverBuilder = new JcaX509v3CertificateBuilder(
                caName, BigInteger.ONE,
                DATE.parse("2019-01-01 00:00:00"), DATE.parse("2039-01-01 00:00:00"),
                serverName, serverPublicKey);

        JcaX509v3CertificateBuilder clientBuilder = new JcaX509v3CertificateBuilder(
                caName, new BigInteger("2"),
                DATE.parse("2019-01-01 00:00:00"), DATE.parse("2039-01-01 00:00:00"),
                clientName, clientPublicKey);

        serverBuilder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        serverBuilder.addExtension(Extension.keyUsage, false, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment));
        serverBuilder.addExtension(Extension.authorityKeyIdentifier, false, new AuthorityKeyIdentifier(caKeyId));
        // https://tools.ietf.org/html/rfc5280#section-4.2.1.12
        serverBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        // https://tools.ietf.org/html/rfc5280#section-4.2.1.6
        serverBuilder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));

        clientBuilder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        clientBuilder.addExtension(Extension.keyUsage, false, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment));
        clientBuilder.addExtension(Extension.authorityKeyIdentifier, false, new AuthorityKeyIdentifier(caKeyId));
        clientBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

        X509Certificate serverX509Certificate = converter.getCertificate(serverBuilder.build(caContentSigner));
        X509Certificate clientX509Certificate = converter.getCertificate(clientBuilder.build(caContentSigner));

        Files.write(new File("target/server.cer").toPath(), serverX509Certificate.getEncoded());
        Files.write(new File("target/client.cer").toPath(), serverX509Certificate.getEncoded());
    }

    @Test
    public void keystores() throws Exception {
        certificates();
        // turn X509 certificates into JKS keystores

        // https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#CertificateFactory
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        Certificate caCer = cf.generateCertificate(new FileInputStream("target/ca.cer"));
        Certificate serverCer = cf.generateCertificate(new FileInputStream("target/server.cer"));
        Certificate clientCer = cf.generateCertificate(new FileInputStream("target/client.cer"));

        KeyFactory kf = KeyFactory.getInstance("RSA", "SunJSSE");

        // java.security.spec.InvalidKeySpecException: Only RSAPrivate(Crt)KeySpec and PKCS8EncodedKeySpec supported for RSA private keys
//        PrivateKey serverKey = kf.generatePrivate(new X509EncodedKeySpec(Files.readAllBytes(new File("target/server-private.key").toPath())));
        PrivateKey serverKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(new File("target/server-private.key").toPath())));
        PrivateKey clientKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(new File("target/client-private.key").toPath())));

        KeyStore serverKeystore = KeyStore.getInstance("JKS", "SUN");
        KeyStore clientKeystore = KeyStore.getInstance("JKS", "SUN");

        serverKeystore.load(null, null);
        serverKeystore.setCertificateEntry("ca", caCer);
        serverKeystore.setKeyEntry("server", serverKey, "passw0rd".toCharArray(), new Certificate[] { serverCer });

        clientKeystore.load(null, null);
        clientKeystore.setCertificateEntry("ca", caCer);
        clientKeystore.setKeyEntry("client", clientKey, "passw0rd".toCharArray(), new Certificate[] { clientCer });

        serverKeystore.store(new FileOutputStream("target/server.jks"), "passw0rd".toCharArray());
        clientKeystore.store(new FileOutputStream("target/client.jks"), "passw0rd".toCharArray());
    }

    @Test
    @Ignore("fails on recent JDK")
    public void sslData() throws Exception {
        System.setProperty("javax.net.debug", "ssl");

        keystores();

        KeyStore serverKeystore = KeyStore.getInstance("JKS", "SUN");
        KeyStore clientKeystore = KeyStore.getInstance("JKS", "SUN");
        serverKeystore.load(new FileInputStream("target/server.jks"), "passw0rd".toCharArray());
        clientKeystore.load(new FileInputStream("target/client.jks"), "passw0rd".toCharArray());

        KeyManagerFactory serverKmf = KeyManagerFactory.getInstance("SunX509", "SunJSSE");
        TrustManagerFactory serverTmf = TrustManagerFactory.getInstance("SunX509", "SunJSSE");
        serverKmf.init(serverKeystore, "passw0rd".toCharArray());
        serverTmf.init(serverKeystore);

        KeyManagerFactory clientKmf = KeyManagerFactory.getInstance("SunX509", "SunJSSE");
        TrustManagerFactory clientTmf = TrustManagerFactory.getInstance("SunX509", "SunJSSE");
        clientKmf.init(clientKeystore, "passw0rd".toCharArray());
        clientTmf.init(clientKeystore);

        SSLContext clientContext = SSLContext.getInstance("TLSv1.2", "SunJSSE");
        SSLContext serverContext = SSLContext.getInstance("TLSv1.2", "SunJSSE");
        clientContext.init(clientKmf.getKeyManagers(), clientTmf.getTrustManagers(), null);
        serverContext.init(serverKmf.getKeyManagers(), serverTmf.getTrustManagers(), null);

        // constants in sun.security.ssl.SSLContextImpl.AbstractTLSContext
        SSLParameters defaultSSLParameters = clientContext.getDefaultSSLParameters();
        SSLParameters supportedSSLParameters = clientContext.getSupportedSSLParameters();

        // +--------------------------------------------------+--------------------------------------------------+
        // | defaultSSLParameters                             | supportedSSLParameters                           |
        // |  cipherSuites: java.lang.String[]                |  cipherSuites: java.lang.String[]                |
        // |    0 = "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384" |    0 = "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384" |
        // |    1 = "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384"   |    1 = "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384"   |
        // |    2 = "TLS_RSA_WITH_AES_256_CBC_SHA256"         |    2 = "TLS_RSA_WITH_AES_256_CBC_SHA256"         |
        // |    3 = "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384"  |    3 = "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384"  |
        // |    4 = "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384"    |    4 = "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384"    |
        // |    5 = "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256"     |    5 = "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256"     |
        // |    6 = "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256"     |    6 = "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256"     |
        // |    7 = "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA"    |    7 = "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA"    |
        // |    8 = "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"      |    8 = "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"      |
        // |    9 = "TLS_RSA_WITH_AES_256_CBC_SHA"            |    9 = "TLS_RSA_WITH_AES_256_CBC_SHA"            |
        // |   10 = "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA"     |   10 = "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA"     |
        // |   11 = "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA"       |   11 = "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA"       |
        // |   12 = "TLS_DHE_RSA_WITH_AES_256_CBC_SHA"        |   12 = "TLS_DHE_RSA_WITH_AES_256_CBC_SHA"        |
        // |   13 = "TLS_DHE_DSS_WITH_AES_256_CBC_SHA"        |   13 = "TLS_DHE_DSS_WITH_AES_256_CBC_SHA"        |
        // |   14 = "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256" |   14 = "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256" |
        // |   15 = "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"   |   15 = "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"   |
        // |   16 = "TLS_RSA_WITH_AES_128_CBC_SHA256"         |   16 = "TLS_RSA_WITH_AES_128_CBC_SHA256"         |
        // |   17 = "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256"  |   17 = "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256"  |
        // |   18 = "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256"    |   18 = "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256"    |
        // |   19 = "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256"     |   19 = "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256"     |
        // |   20 = "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256"     |   20 = "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256"     |
        // |   21 = "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA"    |   21 = "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA"    |
        // |   22 = "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"      |   22 = "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"      |
        // |   23 = "TLS_RSA_WITH_AES_128_CBC_SHA"            |   23 = "TLS_RSA_WITH_AES_128_CBC_SHA"            |
        // |   24 = "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA"     |   24 = "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA"     |
        // |   25 = "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA"       |   25 = "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA"       |
        // |   26 = "TLS_DHE_RSA_WITH_AES_128_CBC_SHA"        |   26 = "TLS_DHE_RSA_WITH_AES_128_CBC_SHA"        |
        // |   27 = "TLS_DHE_DSS_WITH_AES_128_CBC_SHA"        |   27 = "TLS_DHE_DSS_WITH_AES_128_CBC_SHA"        |
        // |   28 = "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384" |   28 = "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384" |
        // |   29 = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256" |   29 = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256" |
        // |   30 = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"   |   30 = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"   |
        // |   31 = "TLS_RSA_WITH_AES_256_GCM_SHA384"         |   31 = "TLS_RSA_WITH_AES_256_GCM_SHA384"         |
        // |   32 = "TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384"  |   32 = "TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384"  |
        // |   33 = "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384"    |   33 = "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384"    |
        // |   34 = "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"     |   34 = "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"     |
        // |   35 = "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384"     |   35 = "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384"     |
        // |   36 = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"   |   36 = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"   |
        // |   37 = "TLS_RSA_WITH_AES_128_GCM_SHA256"         |   37 = "TLS_RSA_WITH_AES_128_GCM_SHA256"         |
        // |   38 = "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256"  |   38 = "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256"  |
        // |   39 = "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256"    |   39 = "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256"    |
        // |   40 = "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"     |   40 = "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"     |
        // |   41 = "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256"     |   41 = "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256"     |
        // |   42 = "TLS_EMPTY_RENEGOTIATION_INFO_SCSV"       |   42 = "TLS_EMPTY_RENEGOTIATION_INFO_SCSV"       |
        // +--------------------------------------------------+--------------------------------------------------+
        // | protocols: java.lang.String[]                    | protocols: java.lang.String[]                    |
        // |                                                  |  0 = "SSLv2Hello"                                |
        // |                                                  |  1 = "SSLv3"                                     |
        // |  0 = "TLSv1"                                     |  2 = "TLSv1"                                     |
        // |  1 = "TLSv1.1"                                   |  3 = "TLSv1.1"                                   |
        // |  2 = "TLSv1.2"                                   |  4 = "TLSv1.2"                                   |
        // +--------------------------------------------------+--------------------------------------------------+
        //
        // sorted ciphers:
        //  - TLS_DHE_DSS_WITH_AES_128_CBC_SHA256
        //  - TLS_DHE_DSS_WITH_AES_128_CBC_SHA
        //  - TLS_DHE_DSS_WITH_AES_128_GCM_SHA256
        //  - TLS_DHE_DSS_WITH_AES_256_CBC_SHA
        //  - TLS_DHE_DSS_WITH_AES_256_CBC_SHA256
        //  - TLS_DHE_DSS_WITH_AES_256_GCM_SHA384
        //  - TLS_DHE_RSA_WITH_AES_128_CBC_SHA256
        //  - TLS_DHE_RSA_WITH_AES_128_CBC_SHA
        //  - TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
        //  - TLS_DHE_RSA_WITH_AES_256_CBC_SHA
        //  - TLS_DHE_RSA_WITH_AES_256_CBC_SHA256
        //  - TLS_DHE_RSA_WITH_AES_256_GCM_SHA384
        //  - TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA
        //  - TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256
        //  - TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256
        //  - TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA
        //  - TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384
        //  - TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384
        //  - TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA
        //  - TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256
        //  - TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
        //  - TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384
        //  - TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA
        //  - TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
        //  - TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
        //  - TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256
        //  - TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
        //  - TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384
        //  - TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
        //  - TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
        //  - TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256
        //  - TLS_ECDH_RSA_WITH_AES_128_CBC_SHA
        //  - TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256
        //  - TLS_ECDH_RSA_WITH_AES_256_CBC_SHA
        //  - TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384
        //  - TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384
        //  - TLS_EMPTY_RENEGOTIATION_INFO_SCSV
        //  - TLS_RSA_WITH_AES_128_CBC_SHA
        //  - TLS_RSA_WITH_AES_128_CBC_SHA256
        //  - TLS_RSA_WITH_AES_128_GCM_SHA256
        //  - TLS_RSA_WITH_AES_256_CBC_SHA256
        //  - TLS_RSA_WITH_AES_256_CBC_SHA
        //  - TLS_RSA_WITH_AES_256_GCM_SHA384

        SSLEngine clientEngine = clientContext.createSSLEngine();
        SSLEngine serverEngine = serverContext.createSSLEngine();

        // only one to choose from
        clientEngine.setEnabledProtocols(new String[] { "TLSv1.2" });
        serverEngine.setEnabledProtocols(new String[] { "TLSv1.2" });
        clientEngine.setEnabledCipherSuites(new String[] { "TLS_RSA_WITH_AES_128_CBC_SHA" });
        serverEngine.setEnabledCipherSuites(new String[] { "TLS_RSA_WITH_AES_128_CBC_SHA" });

        clientEngine.setUseClientMode(true);
        serverEngine.setUseClientMode(false);

        assertNull(clientEngine.getHandshakeSession());
        SSLSession clientSession = clientEngine.getSession();
        assertNotNull(clientSession);
        assertNull(serverEngine.getHandshakeSession());
        SSLSession serverSession = serverEngine.getSession();
        assertNotNull(serverSession);

        assertNotSame(clientContext, serverContext);
        assertNotSame(clientEngine, serverEngine);

        ByteBuffer clientRaw = ByteBuffer.allocate(clientSession.getApplicationBufferSize());
        ByteBuffer clientEncrypted = ByteBuffer.allocate(clientSession.getPacketBufferSize());
        ByteBuffer serverEncrypted = ByteBuffer.allocate(clientSession.getPacketBufferSize());
        ByteBuffer serverRaw = ByteBuffer.allocate(clientSession.getApplicationBufferSize());

        SSLEngineResult.HandshakeStatus clientHandshakeStatus;
        SSLEngineResult.HandshakeStatus serverHandshakeStatus;

        SSLEngineResult result;

        SSLParameters clientSSLParameters = clientEngine.getSSLParameters();
        SSLParameters serverSSLParameters = serverEngine.getSSLParameters();

        // handshake example

        Thread.currentThread().setName("CLIENT");

        clientEngine.beginHandshake();

// SSL: Allow unsafe renegotiation: false
// SSL: Allow legacy hello messages: true
// SSL: Is initial handshake: true
// SSL: Is secure renegotiation: false
// SSL: %% No cached client session
// SSL: update handshake state: client_hello[1]
// SSL: upcoming handshake states: server_hello[2]
// SSL: *** ClientHello, TLSv1.2
// SSL: RandomCookie:  GMT: 1556182194 bytes = { 63, 221, 98, 29, 41, 196, 197, 193, 54, 86, 15, 209, 184, 200, 95, 3, 163, 192, 227, 91, 132, 139, 96, 94, 48, 226, 106, 178 }
// SSL: Session ID:  {}
// SSL: Cipher Suites: [TLS_RSA_WITH_AES_128_CBC_SHA]
// SSL: Compression Methods:  { 0 }
// SSL: Extension signature_algorithms, signature_algorithms: SHA512withECDSA, SHA512withRSA, SHA384withECDSA, SHA384withRSA, SHA256withECDSA, SHA256withRSA, SHA256withDSA, SHA224withECDSA, SHA224withRSA, SHA224withDSA, SHA1withECDSA, SHA1withRSA, SHA1withDSA
// SSL: Extension extended_master_secret
// SSL: Extension renegotiation_info, renegotiated_connection: <empty>
// SSL: ***
// SSL: CLIENT, WRITE: TLSv1.2 Handshake, length = 88

        Thread.currentThread().setName("SERVER");

        serverEngine.beginHandshake();

// SSL: Allow unsafe renegotiation: false
// SSL: Allow legacy hello messages: true
// SSL: Is initial handshake: true
// SSL: Is secure renegotiation: false

        clientHandshakeStatus = clientEngine.getHandshakeStatus();
        serverHandshakeStatus = serverEngine.getHandshakeStatus();

        assertThat(clientHandshakeStatus, equalTo(SSLEngineResult.HandshakeStatus.NEED_WRAP));
        assertThat(serverHandshakeStatus, equalTo(SSLEngineResult.HandshakeStatus.NEED_UNWRAP));

        result = clientEngine.wrap(prepareToSend(clientRaw), readyToWrite(clientEncrypted));
        shouldBe(result, clientEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_UNWRAP);

        serverEncrypted = transfer(clientEncrypted);

        result = serverEngine.unwrap(serverEncrypted, readyToWrite(serverRaw));

// SSL: SERVER, READ: TLSv1.2 Handshake, length = 88

        assertThat(serverRaw.flip().limit(), equalTo(0));
        shouldBe(result, serverEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_TASK);
        serverEngine.getDelegatedTask().run();

// SSL: check handshake state: client_hello[1]
// SSL: update handshake state: client_hello[1]
// SSL: upcoming handshake states: server_hello[2]
// SSL: *** ClientHello, TLSv1.2
// SSL: RandomCookie:  GMT: 1556182194 bytes = { 63, 221, 98, 29, 41, 196, 197, 193, 54, 86, 15, 209, 184, 200, 95, 3, 163, 192, 227, 91, 132, 139, 96, 94, 48, 226, 106, 178 }
// SSL: Session ID:  {}
// SSL: Cipher Suites: [TLS_RSA_WITH_AES_128_CBC_SHA]
// SSL: Compression Methods:  { 0 }
// SSL: Extension signature_algorithms, signature_algorithms: SHA512withECDSA, SHA512withRSA, SHA384withECDSA, SHA384withRSA, SHA256withECDSA, SHA256withRSA, SHA256withDSA, SHA224withECDSA, SHA224withRSA, SHA224withDSA, SHA1withECDSA, SHA1withRSA, SHA1withDSA
// SSL: Extension extended_master_secret
// SSL: Extension renegotiation_info, renegotiated_connection: <empty>
// SSL: ***
// SSL: %% Initialized:  [Session-1, SSL_NULL_WITH_NULL_NULL]
// SSL: matching alias: server
// SSL: Standard ciphersuite chosen: TLS_RSA_WITH_AES_128_CBC_SHA
// SSL: %% Negotiating:  [Session-1, TLS_RSA_WITH_AES_128_CBC_SHA]
// SSL: *** ServerHello, TLSv1.2
// SSL: RandomCookie:  GMT: 1556182194 bytes = { 67, 61, 34, 65, 141, 193, 223, 62, 27, 106, 244, 143, 163, 240, 211, 195, 74, 183, 208, 178, 146, 195, 98, 238, 1, 176, 196, 201 }
// SSL: Session ID:  {93, 193, 117, 178, 97, 207, 216, 226, 195, 76, 201, 115, 30, 45, 243, 233, 125, 234, 108, 54, 123, 165, 1, 82, 104, 235, 17, 253, 149, 207, 68, 198}
// SSL: Cipher Suite: TLS_RSA_WITH_AES_128_CBC_SHA
// SSL: Compression Method: 0
// SSL: Extension renegotiation_info, renegotiated_connection: <empty>
// SSL: Extension extended_master_secret
// SSL: ***
// SSL: Cipher suite:  TLS_RSA_WITH_AES_128_CBC_SHA
// SSL: update handshake state: server_hello[2]
// SSL: upcoming handshake states: server certificate[11]
// SSL: upcoming handshake states: certificate_request[13](optional)
// SSL: upcoming handshake states: server_hello_done[14]
// SSL: upcoming handshake states: client certificate[11](optional)
// SSL: upcoming handshake states: client_key_exchange[16]
// SSL: upcoming handshake states: certificate_verify[15](optional)
// SSL: upcoming handshake states: client change_cipher_spec[-1]
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: *** Certificate chain
// SSL: chain [0] = [
// SSL: [
// SSL:   Version: V3
// SSL:   Subject: CN=server
// SSL:   Signature Algorithm: SHA1withRSA, OID = 1.2.840.113549.1.1.5
// SSL:
// SSL:   Key:  Sun RSA public key, 2048 bits
// SSL:   modulus: 18563849677672472942690055468906383002607917129922213643867616745199297276564393986478112917295326111665063598567561415041155103235058645042168104505185206736330035734422569542739479356149010053341795092267433851697013853056986233350641522327241725472443988014414773109581492443091365770326946307388305944454437573394669783330316327664851456594342453312608742740732587595603343771291331709684360723382662135860879695198300470056101296486839821976598676258011617851654409035668282293404001710891662331704520344519975190246103217200329090793089954185502831682057017325756608530852236818140402765664879557942908031027007
// SSL:   public exponent: 65537
// SSL:   Validity: [From: Tue Jan 01 01:00:00 CET 2019,
// SSL:                To: Sat Jan 01 01:00:00 CET 2039]
// SSL:   Issuer: CN=CA
// SSL:   SerialNumber: [    01]
// SSL:
// SSL: Certificate Extensions: 5
// SSL: [1]: ObjectId: 2.5.29.35 Criticality=false
// SSL: AuthorityKeyIdentifier [
// SSL: KeyIdentifier [
// SSL: 0000: BF 7B 1A 8B A7 A3 2D 24   29 1C 5A 94 1E F2 2E 6B  ......-$).Z....k
// SSL: 0010: 0C 02 D5 21                                        ...!
// SSL: ]
// SSL: ]
// SSL:
// SSL: [2]: ObjectId: 2.5.29.19 Criticality=false
// SSL: BasicConstraints:[
// SSL:   CA:false
// SSL:   PathLen: undefined
// SSL: ]
// SSL:
// SSL: [3]: ObjectId: 2.5.29.37 Criticality=false
// SSL: ExtendedKeyUsages [
// SSL:   serverAuth
// SSL: ]
// SSL:
// SSL: [4]: ObjectId: 2.5.29.15 Criticality=false
// SSL: KeyUsage [
// SSL:   DigitalSignature
// SSL:   Non_repudiation
// SSL:   Key_Encipherment
// SSL: ]
// SSL:
// SSL: [5]: ObjectId: 2.5.29.17 Criticality=false
// SSL: SubjectAlternativeName [
// SSL:   IPAddress: 127.0.0.1
// SSL: ]
// SSL:
// SSL: ]
// SSL:   Algorithm: [SHA1withRSA]
// SSL:   Signature:
// SSL: 0000: B6 E2 F2 A5 44 52 8D 48   B9 6E 1A 64 FD 36 94 BC  ....DR.H.n.d.6..
// SSL: 0010: 94 49 66 8F 9C 02 65 53   61 90 7B 69 8C C2 75 AE  .If...eSa..i..u.
// SSL: 0020: CF 56 17 6F 76 EF 5E 02   CA 8F 4B B7 DA 2B 88 71  .V.ov.^...K..+.q
// SSL: 0030: 55 D2 13 1D 81 34 03 0E   2D 8B 7F 09 4E F4 2A B5  U....4..-...N.*.
// SSL: 0040: E6 D9 E9 3A 69 63 74 BB   B0 11 63 3A E6 50 99 B8  ...:ict...c:.P..
// SSL: 0050: 59 F1 23 2A 52 DD 6B E6   89 57 57 4D 88 5B 11 21  Y.#*R.k..WWM.[.!
// SSL: 0060: B6 2E 5D 67 5D 4B 20 9D   F9 F2 3E 6E F8 A8 A3 56  ..]g]K ...>n...V
// SSL: 0070: 95 0B 0A 78 0D 72 0B 04   87 C0 59 70 75 8A F3 A4  ...x.r....Ypu...
// SSL: 0080: D2 C7 F9 E3 E0 3B E7 48   76 26 48 A9 15 2E 7F E4  .....;.Hv&H.....
// SSL: 0090: 84 2A D1 96 2A 4A 13 29   53 67 40 B0 7E 09 AE EE  .*..*J.)Sg@.....
// SSL: 00A0: 92 BE E6 51 5C 9D EC 89   61 3B C1 D6 99 F5 B2 29  ...Q\...a;.....)
// SSL: 00B0: 6E 24 99 36 0E 61 F8 81   7C 2E 18 35 78 50 92 10  n$.6.a.....5xP..
// SSL: 00C0: DA 45 5D 32 9A 38 74 B1   58 B9 DD 1D 6C 93 FC F0  .E]2.8t.X...l...
// SSL: 00D0: E4 DE 8B 64 8B 89 11 B0   94 EE EC 03 41 D2 96 B8  ...d........A...
// SSL: 00E0: 18 10 04 21 7F 72 98 A4   EC C2 A3 C3 56 7A B7 C4  ...!.r......Vz..
// SSL: 00F0: 0C 52 CD A8 4F E1 76 CE   21 23 5D 04 63 53 72 D1  .R..O.v.!#].cSr.
// SSL:
// SSL: ]
// SSL: ***
// SSL: update handshake state: certificate[11]
// SSL: upcoming handshake states: certificate_request[13](optional)
// SSL: upcoming handshake states: server_hello_done[14]
// SSL: upcoming handshake states: client certificate[11](optional)
// SSL: upcoming handshake states: client_key_exchange[16]
// SSL: upcoming handshake states: certificate_verify[15](optional)
// SSL: upcoming handshake states: client change_cipher_spec[-1]
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: *** ServerHelloDone
// SSL: update handshake state: server_hello_done[14]
// SSL: upcoming handshake states: client certificate[11](optional)
// SSL: upcoming handshake states: client_key_exchange[16]
// SSL: upcoming handshake states: certificate_verify[15](optional)
// SSL: upcoming handshake states: client change_cipher_spec[-1]
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: SERVER, WRITE: TLSv1.2 Handshake, length = 865

        shouldBe(serverEngine, SSLEngineResult.HandshakeStatus.NEED_WRAP);

        result = serverEngine.wrap(prepareToSend(serverRaw), readyToWrite(serverEncrypted));
        shouldBe(result, serverEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_UNWRAP);

        clientEncrypted = transfer(serverEncrypted);

        Thread.currentThread().setName("CLIENT");

        result = clientEngine.unwrap(clientEncrypted, readyToWrite(clientRaw));

// SSL: CLIENT, READ: TLSv1.2 Handshake, length = 865

        assertThat(clientRaw.flip().limit(), equalTo(0));
        shouldBe(result, clientEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_TASK);
        clientEngine.getDelegatedTask().run();

// SSL: check handshake state: server_hello[2]
// SSL: *** ServerHello, TLSv1.2
// SSL: RandomCookie:  GMT: 1556182194 bytes = { 67, 61, 34, 65, 141, 193, 223, 62, 27, 106, 244, 143, 163, 240, 211, 195, 74, 183, 208, 178, 146, 195, 98, 238, 1, 176, 196, 201 }
// SSL: Session ID:  {93, 193, 117, 178, 97, 207, 216, 226, 195, 76, 201, 115, 30, 45, 243, 233, 125, 234, 108, 54, 123, 165, 1, 82, 104, 235, 17, 253, 149, 207, 68, 198}
// SSL: Cipher Suite: TLS_RSA_WITH_AES_128_CBC_SHA
// SSL: Compression Method: 0
// SSL: Extension renegotiation_info, renegotiated_connection: <empty>
// SSL: Extension extended_master_secret
// SSL: ***
// SSL: %% Initialized:  [Session-2, TLS_RSA_WITH_AES_128_CBC_SHA]
// SSL: ** TLS_RSA_WITH_AES_128_CBC_SHA
// SSL: update handshake state: server_hello[2]
// SSL: upcoming handshake states: server certificate[11]
// SSL: upcoming handshake states: certificate_request[13](optional)
// SSL: upcoming handshake states: server_hello_done[14]
// SSL: upcoming handshake states: client certificate[11](optional)
// SSL: upcoming handshake states: client_key_exchange[16]
// SSL: upcoming handshake states: certificate_verify[15](optional)
// SSL: upcoming handshake states: client change_cipher_spec[-1]
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: check handshake state: certificate[11]
// SSL: update handshake state: certificate[11]
// SSL: upcoming handshake states: certificate_request[13](optional)
// SSL: upcoming handshake states: server_hello_done[14]
// SSL: upcoming handshake states: client certificate[11](optional)
// SSL: upcoming handshake states: client_key_exchange[16]
// SSL: upcoming handshake states: certificate_verify[15](optional)
// SSL: upcoming handshake states: client change_cipher_spec[-1]
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: *** Certificate chain
// SSL: chain [0] = [
// SSL: [
// SSL:   Version: V3
// SSL:   Subject: CN=server
// SSL:   Signature Algorithm: SHA1withRSA, OID = 1.2.840.113549.1.1.5
// SSL:
// SSL:   Key:  Sun RSA public key, 2048 bits
// SSL:   modulus: 18563849677672472942690055468906383002607917129922213643867616745199297276564393986478112917295326111665063598567561415041155103235058645042168104505185206736330035734422569542739479356149010053341795092267433851697013853056986233350641522327241725472443988014414773109581492443091365770326946307388305944454437573394669783330316327664851456594342453312608742740732587595603343771291331709684360723382662135860879695198300470056101296486839821976598676258011617851654409035668282293404001710891662331704520344519975190246103217200329090793089954185502831682057017325756608530852236818140402765664879557942908031027007
// SSL:   public exponent: 65537
// SSL:   Validity: [From: Tue Jan 01 01:00:00 CET 2019,
// SSL:                To: Sat Jan 01 01:00:00 CET 2039]
// SSL:   Issuer: CN=CA
// SSL:   SerialNumber: [    01]
// SSL:
// SSL: Certificate Extensions: 5
// SSL: [1]: ObjectId: 2.5.29.35 Criticality=false
// SSL: AuthorityKeyIdentifier [
// SSL: KeyIdentifier [
// SSL: 0000: BF 7B 1A 8B A7 A3 2D 24   29 1C 5A 94 1E F2 2E 6B  ......-$).Z....k
// SSL: 0010: 0C 02 D5 21                                        ...!
// SSL: ]
// SSL: ]
// SSL:
// SSL: [2]: ObjectId: 2.5.29.19 Criticality=false
// SSL: BasicConstraints:[
// SSL:   CA:false
// SSL:   PathLen: undefined
// SSL: ]
// SSL:
// SSL: [3]: ObjectId: 2.5.29.37 Criticality=false
// SSL: ExtendedKeyUsages [
// SSL:   serverAuth
// SSL: ]
// SSL:
// SSL: [4]: ObjectId: 2.5.29.15 Criticality=false
// SSL: KeyUsage [
// SSL:   DigitalSignature
// SSL:   Non_repudiation
// SSL:   Key_Encipherment
// SSL: ]
// SSL:
// SSL: [5]: ObjectId: 2.5.29.17 Criticality=false
// SSL: SubjectAlternativeName [
// SSL:   IPAddress: 127.0.0.1
// SSL: ]
// SSL:
// SSL: ]
// SSL:   Algorithm: [SHA1withRSA]
// SSL:   Signature:
// SSL: 0000: B6 E2 F2 A5 44 52 8D 48   B9 6E 1A 64 FD 36 94 BC  ....DR.H.n.d.6..
// SSL: 0010: 94 49 66 8F 9C 02 65 53   61 90 7B 69 8C C2 75 AE  .If...eSa..i..u.
// SSL: 0020: CF 56 17 6F 76 EF 5E 02   CA 8F 4B B7 DA 2B 88 71  .V.ov.^...K..+.q
// SSL: 0030: 55 D2 13 1D 81 34 03 0E   2D 8B 7F 09 4E F4 2A B5  U....4..-...N.*.
// SSL: 0040: E6 D9 E9 3A 69 63 74 BB   B0 11 63 3A E6 50 99 B8  ...:ict...c:.P..
// SSL: 0050: 59 F1 23 2A 52 DD 6B E6   89 57 57 4D 88 5B 11 21  Y.#*R.k..WWM.[.!
// SSL: 0060: B6 2E 5D 67 5D 4B 20 9D   F9 F2 3E 6E F8 A8 A3 56  ..]g]K ...>n...V
// SSL: 0070: 95 0B 0A 78 0D 72 0B 04   87 C0 59 70 75 8A F3 A4  ...x.r....Ypu...
// SSL: 0080: D2 C7 F9 E3 E0 3B E7 48   76 26 48 A9 15 2E 7F E4  .....;.Hv&H.....
// SSL: 0090: 84 2A D1 96 2A 4A 13 29   53 67 40 B0 7E 09 AE EE  .*..*J.)Sg@.....
// SSL: 00A0: 92 BE E6 51 5C 9D EC 89   61 3B C1 D6 99 F5 B2 29  ...Q\...a;.....)
// SSL: 00B0: 6E 24 99 36 0E 61 F8 81   7C 2E 18 35 78 50 92 10  n$.6.a.....5xP..
// SSL: 00C0: DA 45 5D 32 9A 38 74 B1   58 B9 DD 1D 6C 93 FC F0  .E]2.8t.X...l...
// SSL: 00D0: E4 DE 8B 64 8B 89 11 B0   94 EE EC 03 41 D2 96 B8  ...d........A...
// SSL: 00E0: 18 10 04 21 7F 72 98 A4   EC C2 A3 C3 56 7A B7 C4  ...!.r......Vz..
// SSL: 00F0: 0C 52 CD A8 4F E1 76 CE   21 23 5D 04 63 53 72 D1  .R..O.v.!#].cSr.
// SSL:
// SSL: ]
// SSL: ***
// SSL: Found trusted certificate:
// SSL: [
// SSL: [
// SSL:   Version: V3
// SSL:   Subject: CN=server
// SSL:   Signature Algorithm: SHA1withRSA, OID = 1.2.840.113549.1.1.5
// SSL:
// SSL:   Key:  Sun RSA public key, 2048 bits
// SSL:   modulus: 18563849677672472942690055468906383002607917129922213643867616745199297276564393986478112917295326111665063598567561415041155103235058645042168104505185206736330035734422569542739479356149010053341795092267433851697013853056986233350641522327241725472443988014414773109581492443091365770326946307388305944454437573394669783330316327664851456594342453312608742740732587595603343771291331709684360723382662135860879695198300470056101296486839821976598676258011617851654409035668282293404001710891662331704520344519975190246103217200329090793089954185502831682057017325756608530852236818140402765664879557942908031027007
// SSL:   public exponent: 65537
// SSL:   Validity: [From: Tue Jan 01 01:00:00 CET 2019,
// SSL:                To: Sat Jan 01 01:00:00 CET 2039]
// SSL:   Issuer: CN=CA
// SSL:   SerialNumber: [    01]
// SSL:
// SSL: Certificate Extensions: 5
// SSL: [1]: ObjectId: 2.5.29.35 Criticality=false
// SSL: AuthorityKeyIdentifier [
// SSL: KeyIdentifier [
// SSL: 0000: BF 7B 1A 8B A7 A3 2D 24   29 1C 5A 94 1E F2 2E 6B  ......-$).Z....k
// SSL: 0010: 0C 02 D5 21                                        ...!
// SSL: ]
// SSL: ]
// SSL:
// SSL: [2]: ObjectId: 2.5.29.19 Criticality=false
// SSL: BasicConstraints:[
// SSL:   CA:false
// SSL:   PathLen: undefined
// SSL: ]
// SSL:
// SSL: [3]: ObjectId: 2.5.29.37 Criticality=false
// SSL: ExtendedKeyUsages [
// SSL:   serverAuth
// SSL: ]
// SSL:
// SSL: [4]: ObjectId: 2.5.29.15 Criticality=false
// SSL: KeyUsage [
// SSL:   DigitalSignature
// SSL:   Non_repudiation
// SSL:   Key_Encipherment
// SSL: ]
// SSL:
// SSL: [5]: ObjectId: 2.5.29.17 Criticality=false
// SSL: SubjectAlternativeName [
// SSL:   IPAddress: 127.0.0.1
// SSL: ]
// SSL:
// SSL: ]
// SSL:   Algorithm: [SHA1withRSA]
// SSL:   Signature:
// SSL: 0000: B6 E2 F2 A5 44 52 8D 48   B9 6E 1A 64 FD 36 94 BC  ....DR.H.n.d.6..
// SSL: 0010: 94 49 66 8F 9C 02 65 53   61 90 7B 69 8C C2 75 AE  .If...eSa..i..u.
// SSL: 0020: CF 56 17 6F 76 EF 5E 02   CA 8F 4B B7 DA 2B 88 71  .V.ov.^...K..+.q
// SSL: 0030: 55 D2 13 1D 81 34 03 0E   2D 8B 7F 09 4E F4 2A B5  U....4..-...N.*.
// SSL: 0040: E6 D9 E9 3A 69 63 74 BB   B0 11 63 3A E6 50 99 B8  ...:ict...c:.P..
// SSL: 0050: 59 F1 23 2A 52 DD 6B E6   89 57 57 4D 88 5B 11 21  Y.#*R.k..WWM.[.!
// SSL: 0060: B6 2E 5D 67 5D 4B 20 9D   F9 F2 3E 6E F8 A8 A3 56  ..]g]K ...>n...V
// SSL: 0070: 95 0B 0A 78 0D 72 0B 04   87 C0 59 70 75 8A F3 A4  ...x.r....Ypu...
// SSL: 0080: D2 C7 F9 E3 E0 3B E7 48   76 26 48 A9 15 2E 7F E4  .....;.Hv&H.....
// SSL: 0090: 84 2A D1 96 2A 4A 13 29   53 67 40 B0 7E 09 AE EE  .*..*J.)Sg@.....
// SSL: 00A0: 92 BE E6 51 5C 9D EC 89   61 3B C1 D6 99 F5 B2 29  ...Q\...a;.....)
// SSL: 00B0: 6E 24 99 36 0E 61 F8 81   7C 2E 18 35 78 50 92 10  n$.6.a.....5xP..
// SSL: 00C0: DA 45 5D 32 9A 38 74 B1   58 B9 DD 1D 6C 93 FC F0  .E]2.8t.X...l...
// SSL: 00D0: E4 DE 8B 64 8B 89 11 B0   94 EE EC 03 41 D2 96 B8  ...d........A...
// SSL: 00E0: 18 10 04 21 7F 72 98 A4   EC C2 A3 C3 56 7A B7 C4  ...!.r......Vz..
// SSL: 00F0: 0C 52 CD A8 4F E1 76 CE   21 23 5D 04 63 53 72 D1  .R..O.v.!#].cSr.
// SSL:
// SSL: ]
// SSL: check handshake state: server_hello_done[14]
// SSL: update handshake state: server_hello_done[14]
// SSL: upcoming handshake states: client certificate[11](optional)
// SSL: upcoming handshake states: client_key_exchange[16]
// SSL: upcoming handshake states: certificate_verify[15](optional)
// SSL: upcoming handshake states: client change_cipher_spec[-1]
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: *** ServerHelloDone
// SSL: *** ClientKeyExchange, RSA PreMasterSecret, TLSv1.2
// SSL: update handshake state: client_key_exchange[16]
// SSL: upcoming handshake states: certificate_verify[15](optional)
// SSL: upcoming handshake states: client change_cipher_spec[-1]
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: CLIENT, WRITE: TLSv1.2 Handshake, length = 262
// SSL: SESSION KEYGEN:
// SSL: PreMaster Secret:
// SSL: 0000: 03 03 24 0F F4 42 D2 2D   08 FF C6 F0 5B A8 56 1B  ..$..B.-....[.V.
// SSL: 0010: 36 7A 24 1B C4 C3 D5 87   67 07 AA 9B 9C 8E 13 6B  6z$.....g......k
// SSL: 0020: C1 E6 FC F7 BE 12 E2 7E   7C 20 46 9D AB 79 F3 3E  ......... F..y.>
// SSL: CONNECTION KEYGEN:
// SSL: Client Nonce:
// SSL: 0000: 5D C1 75 B2 3F DD 62 1D   29 C4 C5 C1 36 56 0F D1  ].u.?.b.)...6V..
// SSL: 0010: B8 C8 5F 03 A3 C0 E3 5B   84 8B 60 5E 30 E2 6A B2  .._....[..`^0.j.
// SSL: Server Nonce:
// SSL: 0000: 5D C1 75 B2 43 3D 22 41   8D C1 DF 3E 1B 6A F4 8F  ].u.C="A...>.j..
// SSL: 0010: A3 F0 D3 C3 4A B7 D0 B2   92 C3 62 EE 01 B0 C4 C9  ....J.....b.....
// SSL: Master Secret:
// SSL: 0000: D7 08 EB A3 E9 EF 4D 6C   83 07 B9 FC FB 7A 04 3D  ......Ml.....z.=
// SSL: 0010: B0 85 B6 55 1C F6 3E B6   FF 1D D7 4E 9A FE 28 83  ...U..>....N..(.
// SSL: 0020: C8 BA 5A 5D 53 6F 63 80   06 83 2C 3E EF 82 87 8D  ..Z]Soc...,>....
// SSL: Client MAC write Secret:
// SSL: 0000: 4E 86 CA 51 4D F4 96 DD   87 2D 7D AC 25 21 13 06  N..QM....-..%!..
// SSL: 0010: BF 86 5B 61                                        ..[a
// SSL: Server MAC write Secret:
// SSL: 0000: CD 8A B7 61 3E 76 41 BB   5E 62 73 4F 01 38 70 B6  ...a>vA.^bsO.8p.
// SSL: 0010: 91 0E AB D8                                        ....
// SSL: Client write key:
// SSL: 0000: FE FD 94 F1 C2 74 B7 CE   5A 8F 53 00 3B 97 64 2B  .....t..Z.S.;.d+
// SSL: Server write key:
// SSL: 0000: 9C C8 AA A6 6E 82 2F 13   2F 44 71 72 D2 B8 92 56  ....n././Dqr...V
// SSL: ... no IV derived for this protocol
// SSL: update handshake state: change_cipher_spec
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: CLIENT, WRITE: TLSv1.2 Change Cipher Spec, length = 1
// SSL: *** Finished
// SSL: verify_data:  { 222, 65, 36, 11, 157, 255, 118, 91, 249, 149, 168, 76 }
// SSL: ***
// SSL: update handshake state: finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: CLIENT, WRITE: TLSv1.2 Handshake, length = 64

        shouldBe(clientEngine, SSLEngineResult.HandshakeStatus.NEED_WRAP);

        result = clientEngine.wrap(prepareToSend(clientRaw), readyToWrite(clientEncrypted));
        shouldBe(result, clientEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_WRAP);

        serverEncrypted = transfer(clientEncrypted);

        Thread.currentThread().setName("SERVER");

        result = serverEngine.unwrap(serverEncrypted, readyToWrite(serverRaw));

// SSL: SERVER, READ: TLSv1.2 Handshake, length = 262

        assertThat(serverRaw.flip().limit(), equalTo(0));
        shouldBe(result, serverEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_TASK);
        serverEngine.getDelegatedTask().run();

// SSL: check handshake state: client_key_exchange[16]
// SSL: update handshake state: client_key_exchange[16]
// SSL: upcoming handshake states: certificate_verify[15](optional)
// SSL: upcoming handshake states: client change_cipher_spec[-1]
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: *** ClientKeyExchange, RSA PreMasterSecret, TLSv1.2
// SSL: SESSION KEYGEN:
// SSL: PreMaster Secret:
// SSL: 0000: 03 03 24 0F F4 42 D2 2D   08 FF C6 F0 5B A8 56 1B  ..$..B.-....[.V.
// SSL: 0010: 36 7A 24 1B C4 C3 D5 87   67 07 AA 9B 9C 8E 13 6B  6z$.....g......k
// SSL: 0020: C1 E6 FC F7 BE 12 E2 7E   7C 20 46 9D AB 79 F3 3E  ......... F..y.>
// SSL: CONNECTION KEYGEN:
// SSL: Client Nonce:
// SSL: 0000: 5D C1 75 B2 3F DD 62 1D   29 C4 C5 C1 36 56 0F D1  ].u.?.b.)...6V..
// SSL: 0010: B8 C8 5F 03 A3 C0 E3 5B   84 8B 60 5E 30 E2 6A B2  .._....[..`^0.j.
// SSL: Server Nonce:
// SSL: 0000: 5D C1 75 B2 43 3D 22 41   8D C1 DF 3E 1B 6A F4 8F  ].u.C="A...>.j..
// SSL: 0010: A3 F0 D3 C3 4A B7 D0 B2   92 C3 62 EE 01 B0 C4 C9  ....J.....b.....
// SSL: Master Secret:
// SSL: 0000: D7 08 EB A3 E9 EF 4D 6C   83 07 B9 FC FB 7A 04 3D  ......Ml.....z.=
// SSL: 0010: B0 85 B6 55 1C F6 3E B6   FF 1D D7 4E 9A FE 28 83  ...U..>....N..(.
// SSL: 0020: C8 BA 5A 5D 53 6F 63 80   06 83 2C 3E EF 82 87 8D  ..Z]Soc...,>....
// SSL: Client MAC write Secret:
// SSL: 0000: 4E 86 CA 51 4D F4 96 DD   87 2D 7D AC 25 21 13 06  N..QM....-..%!..
// SSL: 0010: BF 86 5B 61                                        ..[a
// SSL: Server MAC write Secret:
// SSL: 0000: CD 8A B7 61 3E 76 41 BB   5E 62 73 4F 01 38 70 B6  ...a>vA.^bsO.8p.
// SSL: 0010: 91 0E AB D8                                        ....
// SSL: Client write key:
// SSL: 0000: FE FD 94 F1 C2 74 B7 CE   5A 8F 53 00 3B 97 64 2B  .....t..Z.S.;.d+
// SSL: Server write key:
// SSL: 0000: 9C C8 AA A6 6E 82 2F 13   2F 44 71 72 D2 B8 92 56  ....n././Dqr...V
// SSL: ... no IV derived for this protocol

        shouldBe(serverEngine, SSLEngineResult.HandshakeStatus.NEED_UNWRAP);

        result = clientEngine.wrap(prepareToSend(clientRaw), readyToWrite(clientEncrypted));
        shouldBe(result, clientEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_WRAP);

        serverEncrypted = transfer(clientEncrypted);

        result = serverEngine.unwrap(serverEncrypted, readyToWrite(serverRaw));

// SSL: SERVER, READ: TLSv1.2 Change Cipher Spec, length = 1
// SSL: update handshake state: change_cipher_spec
// SSL: upcoming handshake states: client finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]

        assertThat(serverRaw.flip().limit(), equalTo(0));
        shouldBe(result, serverEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_UNWRAP);

        result = clientEngine.wrap(prepareToSend(clientRaw), readyToWrite(clientEncrypted));
        shouldBe(result, clientEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_UNWRAP);

        serverEncrypted = transfer(clientEncrypted);

        result = serverEngine.unwrap(serverEncrypted, readyToWrite(serverRaw));

// SSL: SERVER, READ: TLSv1.2 Handshake, length = 64
// SSL: check handshake state: finished[20]
// SSL: update handshake state: finished[20]
// SSL: upcoming handshake states: server change_cipher_spec[-1]
// SSL: upcoming handshake states: server finished[20]
// SSL: *** Finished
// SSL: verify_data:  { 222, 65, 36, 11, 157, 255, 118, 91, 249, 149, 168, 76 }
// SSL: ***
// SSL: update handshake state: change_cipher_spec
// SSL: upcoming handshake states: server finished[20]
// SSL: SERVER, WRITE: TLSv1.2 Change Cipher Spec, length = 1
// SSL: *** Finished
// SSL: verify_data:  { 12, 5, 165, 253, 190, 131, 80, 155, 163, 127, 160, 252 }
// SSL: ***
// SSL: update handshake state: finished[20]
// SSL: SERVER, WRITE: TLSv1.2 Handshake, length = 64
// SSL: %% Cached server session: [Session-1, TLS_RSA_WITH_AES_128_CBC_SHA]

        assertThat(serverRaw.flip().limit(), equalTo(0));
        shouldBe(result, serverEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_WRAP);

        result = serverEngine.wrap(prepareToSend(serverRaw), readyToWrite(serverEncrypted));
        shouldBe(result, serverEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_WRAP);

        clientEncrypted = transfer(serverEncrypted);

        Thread.currentThread().setName("CLIENT");

        result = clientEngine.unwrap(clientEncrypted, readyToWrite(clientRaw));

// SSL: CLIENT, READ: TLSv1.2 Change Cipher Spec, length = 1
// SSL: update handshake state: change_cipher_spec
// SSL: upcoming handshake states: server finished[20]

        assertThat(clientRaw.flip().limit(), equalTo(0));
        shouldBe(result, clientEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_UNWRAP);

        result = serverEngine.wrap(prepareToSend(serverRaw), readyToWrite(serverEncrypted));
        shouldBe(result, serverEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING);

        clientEncrypted = transfer(serverEncrypted);

        result = clientEngine.unwrap(clientEncrypted, readyToWrite(clientRaw));

// SSL: CLIENT, READ: TLSv1.2 Handshake, length = 64
// SSL: check handshake state: finished[20]
// SSL: update handshake state: finished[20]
// SSL: *** Finished
// SSL: verify_data:  { 12, 5, 165, 253, 190, 131, 80, 155, 163, 127, 160, 252 }
// SSL: ***
// SSL: %% Cached client session: [Session-2, TLS_RSA_WITH_AES_128_CBC_SHA]

        assertThat(clientRaw.flip().limit(), equalTo(0));
        shouldBe(result, clientEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING);

        result = clientEngine.wrap(prepareToSend(clientRaw, "hello world"), readyToWrite(clientEncrypted));

// SSL: CLIENT, WRITE: TLSv1.2 Application Data, length = 11

        shouldBe(result, clientEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING);

        serverEncrypted = transfer(clientEncrypted);

        result = serverEngine.unwrap(serverEncrypted, readyToWrite(serverRaw));
        shouldBe(result, serverEngine, SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING);

        serverRaw.flip();
        assertThat(new String(serverRaw.array(), 0, serverRaw.limit()), equalTo("hello world"));

        SSLSessionContext serverSessionContext = serverContext.getServerSessionContext();
        SSLSessionContext clientSessionContext = clientContext.getClientSessionContext();

        for (Enumeration<byte[]> e = serverSessionContext.getIds(); e.hasMoreElements(); ) {
            SSLSession session = serverSessionContext.getSession(e.nextElement());
            assertThat(serverEngine.getSession(), equalTo(session));
            sessionInfo(session);
        }

        for (Enumeration<byte[]> e = clientSessionContext.getIds(); e.hasMoreElements(); ) {
            SSLSession session = clientSessionContext.getSession(e.nextElement());
            assertThat(clientEngine.getSession(), equalTo(session));
            sessionInfo(session);
        }

        Thread.currentThread().setName("CLIENT");

        // client closes ssl connection.
        clientEngine.closeOutbound();

// SSL: CLIENT, called closeOutbound()
// SSL: CLIENT, closeOutboundInternal()
// SSL: CLIENT, SEND TLSv1.2 ALERT:  warning, description = close_notify
// SSL: CLIENT, WRITE: TLSv1.2 Alert, length = 48

        // this shouldn't be closed before getting notification, otherwise, we'll get:
//        clientEngine.closeInbound();

// SSL: CLIENT, called closeInbound()
// SSL: CLIENT, fatal error: 80: Inbound closed before receiving peer's close_notify: possible truncation attack?

        result = clientEngine.wrap(prepareToSend(clientRaw), readyToWrite(clientEncrypted));
        shouldBe(result, clientEngine, SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NEED_UNWRAP);

        serverEncrypted = transfer(clientEncrypted);

        Thread.currentThread().setName("SERVER");

        result = serverEngine.unwrap(serverEncrypted, readyToWrite(serverRaw));

// SSL: SERVER, READ: TLSv1.2 Alert, length = 48
// SSL: SERVER, RECV TLSv1.2 ALERT:  warning, close_notify
// SSL: SERVER, closeInboundInternal()
// SSL: SERVER, closeOutboundInternal()
// SSL: SERVER, SEND TLSv1.2 ALERT:  warning, description = close_notify
// SSL: SERVER, WRITE: TLSv1.2 Alert, length = 48

        shouldBe(result, serverEngine, SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NEED_WRAP);

        serverEngine.getSession().invalidate();

// SSL: %% Invalidated:  [Session-1, TLS_RSA_WITH_AES_128_CBC_SHA]

        result = serverEngine.wrap(prepareToSend(serverRaw), readyToWrite(serverEncrypted));
        shouldBe(result, serverEngine, SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING);

        clientEncrypted = transfer(serverEncrypted);

        Thread.currentThread().setName("CLIENT");

        result = clientEngine.unwrap(clientEncrypted, readyToWrite(clientRaw));

// SSL: CLIENT, READ: TLSv1.2 Alert, length = 48
// SSL: CLIENT, RECV TLSv1.2 ALERT:  warning, close_notify
// SSL: CLIENT, closeInboundInternal()
// SSL: CLIENT, closeOutboundInternal()

        assertThat(clientRaw.flip().limit(), equalTo(0));
        shouldBe(result, clientEngine, SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING);

        assertTrue(clientEngine.isOutboundDone());
        assertTrue(clientEngine.isInboundDone());
    }

    private void sessionInfo(SSLSession session) {
        try {
            LOG.info("Session from {}:{} - {}", session.getPeerHost(), session.getPeerPort(), session.getPeerPrincipal().toString());
        } catch (SSLPeerUnverifiedException e) {
            LOG.info("Session from {}:{} - {}", session.getPeerHost(), session.getPeerPort(), session.getLocalPrincipal().toString());
        }
    }

    private ByteBuffer prepareToSend(ByteBuffer bb) {
        bb.clear(); // empty the buffer
        bb.flip();  // switch from write to read
        return bb;
    }

    private ByteBuffer prepareToSend(ByteBuffer bb, String msg) {
        bb.clear();             // empty the buffer
        bb.put(msg.getBytes()); // write message
        bb.flip();              // switch from write to read
        return bb;
    }

    private ByteBuffer readyToWrite(ByteBuffer bb) {
        bb.clear(); // empty the buffer and keep in write-mode
        return bb;
    }

    private void shouldBe(SSLEngineResult result, SSLEngine engine, SSLEngineResult.Status status, SSLEngineResult.HandshakeStatus hsStatus) {
        assertThat(result.getStatus(), equalTo(status));
        SSLEngineResult.HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
        assertThat(handshakeStatus, equalTo(hsStatus));
    }

    private void shouldBe(SSLEngine engine, SSLEngineResult.HandshakeStatus hsStatus) {
        SSLEngineResult.HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
        assertThat(handshakeStatus, equalTo(hsStatus));
    }

    private ByteBuffer transfer(ByteBuffer from) {
        ByteBuffer to = from.duplicate();
        to.flip();
        return to;
    }

    public static Object getField(Object object, String fieldName) {
        String[] names = fieldName.split("\\.");
        for (String name : names) {
            Field f = null;
            try {
                f = object.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                try {
                    f = object.getClass().getSuperclass().getDeclaredField(name);
                } catch (NoSuchFieldException ex) {
                    try {
                        f = object.getClass().getSuperclass().getSuperclass().getDeclaredField(name);
                    } catch (NoSuchFieldException exx) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }
            }
            f.setAccessible(true);
            try {
                object = f.get(object);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        return object;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getField(Object object, String fieldName, Class<T> clazz) {
        return (T) getField(object, fieldName);
    }

}
