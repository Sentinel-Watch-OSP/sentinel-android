package com.samourai.wallet.bip47.rpc.obpp05;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.ECKey;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;

import com.samourai.wallet.util.Util;

public class PaymentAddress {

    private static final Logger log = LoggerFactory.getLogger(PaymentAddress.class);

    private PaymentCode paymentCode = null;
    private int index = 0;
    private byte[] privKey = null;
    private byte[] coin = null;

    private static final X9ECParameters CURVE_PARAMS = CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters CURVE = new ECDomainParameters(CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());

    public static final byte[] BTC_MAINNET_COIN = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x00000000).array();
    public static final byte[] BTC_TESTNET_COIN = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x00000001).array();

    private PaymentAddress()    { ; }

    public PaymentAddress(PaymentCode paymentCode, byte[] coin)   {
        this.paymentCode = paymentCode;
        this.index = 0;
        this.privKey = null;
        this.coin = coin;
    }

    public PaymentAddress(PaymentCode paymentCode, int index, byte[] privKey, byte[] coin)    {
        this.paymentCode = paymentCode;
        this.index = index;
        this.privKey = privKey;
        this.coin = coin;
    }

    public PaymentCode getPaymentCode() {
        return paymentCode;
    }

    public void setPaymentCode(PaymentCode paymentCode) {
        this.paymentCode = paymentCode;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public byte[] getPrivKey() {
        return privKey;
    }

    public void setIndexAndPrivKey(int index, byte[] privKey) {
        this.index = index;
        this.privKey = privKey;
    }

    public void setPrivKey(byte[] privKey) {
        this.privKey = privKey;
    }

    public ECPoint get_sG() throws AddressFormatException, InvalidKeyException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException  {
        return CURVE_PARAMS.getG().multiply(getSecretPoint());
    }

    public SecretPoint getSharedSecret() throws AddressFormatException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {
        return sharedSecret();
    }

    public BigInteger getSecretPoint() throws AddressFormatException, InvalidKeyException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException  {
        return secretPoint();
    }

    public ECPoint getECPoint() throws AddressFormatException, NoSuchAlgorithmException    {
        ECKey ecKey = ECKey.fromPublicOnly(paymentCode.derivePubkey(index));
        return ecKey.getPubKeyPoint();
    }

    public byte[] hashSharedSecret() throws AddressFormatException, InvalidKeyException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(getSharedSecret().ECDHSecretAsBytes());
        return hash;
    }

    private ECPoint get_sG(BigInteger s) {
        return CURVE_PARAMS.getG().multiply(s);
    }

    public ECKey getSendAddress() throws AddressFormatException, InvalidKeyException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {

        BigInteger s = getPointValue();

        ECPoint ecPoint = getECPoint();
        ECPoint sG = get_sG(s);
        ECKey eckey = ECKey.fromPublicOnly(ecPoint.add(sG).getEncoded(true));

        return eckey;
    }

    public ECKey getReceiveAddress() throws AddressFormatException, InvalidKeyException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {

        BigInteger s = getPointValue();

        BigInteger privKeyValue = ECKey.fromPrivate(privKey).getPrivKey();
        ECKey eckey = ECKey.fromPrivate(addSecp256k1(privKeyValue, s));

        return eckey;
    }

    private BigInteger getPointValue() throws AddressFormatException, InvalidKeyException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {

        SecretPoint secretPoint = new SecretPoint(privKey, paymentCode.derivePubkey(index));
        byte[] mac_data = Util.getHMAC(secretPoint.ECDHSecretAsBytes(), coin);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(mac_data);

        BigInteger s = new BigInteger(1, hash);
        if(!isSecp256k1(s)) {
            return null;
        }

        return s;
    }

    private BigInteger addSecp256k1(BigInteger b1, BigInteger b2) {

        BigInteger ret = b1.add(b2);

        if(ret.bitLength() > CURVE.getN().bitLength()) {
            return ret.mod(CURVE.getN());
        }

        return ret;
    }

    private SecretPoint sharedSecret() throws AddressFormatException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {
        return new SecretPoint(privKey, paymentCode.derivePubkey(index));
    }

    private boolean isSecp256k1(BigInteger b) {

        if(b.compareTo(BigInteger.ONE) <= 0 || b.bitLength() > CURVE.getN().bitLength()) {
            return false;
        }

        return true;
    }

    private BigInteger secretPoint() throws AddressFormatException, InvalidKeyException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException  {
        //
        // convert hash to value 's'
        //
        BigInteger s = new BigInteger(1, hashSharedSecret());
        //
        // check that 's' is on the secp256k1 curve
        //
        if(!isSecp256k1(s))    {
            log.warn("Secret point not on secp256k1 curve");
            return null;
        }

        return s;
    }

}
