package com.fish5.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.util.UndashedUuid;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The Microsoft -> Xbox Live -> Minecraft login chain: OAuth device code (or a saved refresh
 * token), Xbox Live device auth, SISU/XSTS authorization, then the Minecraft services launcher
 * login. Mirrors the official launcher's flow closely enough that it doesn't trip anti-bot checks.
 */
@Environment(EnvType.CLIENT)
final class MicrosoftLogin {
    private static final String CLIENT_ID = "00000000402b5328";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final long FILETIME_EPOCH_OFFSET = 11644473600L;
    private static final long SLOW_DOWN_MS = 5000L;

    private MicrosoftLogin() {
    }

    static DeviceCode requestDeviceCode() throws InterruptedException {
        AuthHttp.Reply reply = AuthHttp.postForm(
            "https://login.live.com/oauth20_connect.srf",
            Map.of("client_id", CLIENT_ID, "scope", SCOPE, "response_type", "device_code")
        );
        String deviceCode = reply.text("device_code");
        String userCode = reply.text("user_code");
        String uri = reply.text("verification_uri");
        String interval = reply.text("interval");
        String expiresIn = reply.text("expires_in");
        if (reply.ok() && deviceCode != null && userCode != null && uri != null && interval != null && expiresIn != null) {
            return new DeviceCode(
                deviceCode, userCode, uri, Long.parseLong(interval) * 1000L, System.currentTimeMillis() + Long.parseLong(expiresIn) * 1000L
            );
        }

        throw new AccountException("Microsoft sign-in couldn't start: " + liveError(reply));
    }

    static MsaToken awaitApproval(DeviceCode code) throws InterruptedException {
        long interval = code.intervalMs();

        while (System.currentTimeMillis() < code.expiresAtMs()) {
            Thread.sleep(interval);
            AuthHttp.Reply reply = AuthHttp.postForm(
                "https://login.live.com/oauth20_token.srf", Map.of("client_id", CLIENT_ID, "grant_type", "device_code", "device_code", code.deviceCode())
            );
            if (reply.ok()) {
                return token(reply);
            }

            String error = reply.text("error");
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                interval += SLOW_DOWN_MS;
                continue;
            }
            if ("authorization_declined".equals(error) || "access_denied".equals(error)) {
                throw new AccountException("Microsoft sign-in was declined in the browser");
            }
            if (!"expired_token".equals(error)) {
                throw new AccountException("Microsoft sign-in failed: " + liveError(reply));
            }
            break;
        }

        throw new AccountException("The Microsoft sign-in code expired - try again");
    }

    static MsaToken refresh(String refreshToken) throws InterruptedException {
        AuthHttp.Reply reply = AuthHttp.postForm(
            "https://login.live.com/oauth20_token.srf",
            Map.of("client_id", CLIENT_ID, "scope", SCOPE, "grant_type", "refresh_token", "refresh_token", refreshToken)
        );
        if (reply.ok()) {
            return token(reply);
        } else if ("invalid_grant".equals(reply.text("error"))) {
            throw new AccountException("This Microsoft login has expired - remove the account and add it again");
        } else {
            throw new AccountException("Microsoft login failed: " + liveError(reply));
        }
    }

    static Credentials minecraft(MsaToken msa) throws InterruptedException {
        KeyPair proofKey = newProofKey();
        JsonObject deviceProperties = new JsonObject();
        deviceProperties.addProperty("DeviceType", "Win32");
        deviceProperties.addProperty("Id", "{" + UUID.randomUUID() + "}");
        deviceProperties.addProperty("AuthMethod", "ProofOfPossession");
        deviceProperties.add("ProofKey", jwk((ECPublicKey) proofKey.getPublic()));
        JsonObject deviceRequest = new JsonObject();
        deviceRequest.add("Properties", deviceProperties);
        deviceRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
        deviceRequest.addProperty("TokenType", "JWT");
        AuthHttp.Reply device = signedPost("https://device.auth.xboxlive.com/device/authenticate", deviceRequest, proofKey, Map.of("x-xbl-contract-version", "1"));
        String deviceToken = device.text("Token");
        if (!device.ok() || deviceToken == null) {
            throw xboxError(device);
        }

        JsonObject sisuRequest = new JsonObject();
        sisuRequest.addProperty("Sandbox", "RETAIL");
        sisuRequest.addProperty("UseModernGamertag", true);
        sisuRequest.addProperty("AppId", CLIENT_ID);
        sisuRequest.addProperty("AccessToken", "t=" + msa.accessToken());
        sisuRequest.addProperty("DeviceToken", deviceToken);
        sisuRequest.add("ProofKey", jwk((ECPublicKey) proofKey.getPublic()));
        sisuRequest.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        AuthHttp.Reply sisu = signedPost("https://sisu.xboxlive.com/authorize", sisuRequest, proofKey, Map.of());
        JsonObject xsts = AuthHttp.object(sisu.json(), "AuthorizationToken");
        String xstsToken = AuthHttp.text(xsts, "Token");
        String userHash = userHash(xsts);
        if (!sisu.ok() || xstsToken == null || userHash == null) {
            throw xboxError(sisu);
        }

        JsonObject loginRequest = new JsonObject();
        loginRequest.addProperty("platform", "PC_LAUNCHER");
        loginRequest.addProperty("xtoken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        AuthHttp.Reply login = AuthHttp.postJson("https://api.minecraftservices.com/launcher/login", loginRequest.toString(), Map.of());
        String gameToken = login.text("access_token");
        if (!login.ok() || gameToken == null) {
            throw new AccountException("Minecraft login failed: " + servicesError(login));
        }

        AuthHttp.Reply profile = AuthHttp.get("https://api.minecraftservices.com/minecraft/profile", gameToken);
        if (profile.status() == 404) {
            throw new AccountException("This Microsoft account doesn't own Minecraft: Java Edition");
        }

        String name = profile.text("name");
        String id = profile.text("id");
        if (profile.ok() && name != null && id != null) {
            return new Credentials(name, UndashedUuid.fromStringLenient(id), gameToken, msa.refreshToken());
        }

        throw new AccountException("Couldn't load the Minecraft profile: " + servicesError(profile));
    }

    private static MsaToken token(AuthHttp.Reply reply) {
        String access = reply.text("access_token");
        String refresh = reply.text("refresh_token");
        if (access != null && refresh != null) {
            return new MsaToken(access, refresh);
        }

        throw new AccountException("Microsoft returned no refresh token, so the account couldn't be saved");
    }

    private static String userHash(JsonObject xsts) {
        JsonObject claims = AuthHttp.object(xsts, "DisplayClaims");
        return (claims == null ? null : claims.get("xui")) instanceof JsonArray list && !list.isEmpty() && list.get(0).isJsonObject()
            ? AuthHttp.text(list.get(0).getAsJsonObject(), "uhs")
            : null;
    }

    private static KeyPair newProofKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new AccountException("This Java runtime can't create the key Xbox Live requires", e);
        }
    }

    private static AuthHttp.Reply signedPost(String url, JsonObject body, KeyPair key, Map<String, String> headers) throws InterruptedException {
        String json = body.toString();
        Map<String, String> signed = new HashMap<>(headers);
        signed.put("Signature", signature(URI.create(url).getPath(), json, key.getPrivate()));
        return AuthHttp.postJson(url, json, signed);
    }

    private static String signature(String path, String body, PrivateKey key) {
        long stamp = (Instant.now().getEpochSecond() + FILETIME_EPOCH_OFFSET) * 10000000L;

        try {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(content);
            out.writeInt(1);
            out.writeByte(0);
            out.writeLong(stamp);
            out.writeByte(0);
            out.write("POST".getBytes(StandardCharsets.UTF_8));
            out.writeByte(0);
            out.write(path.getBytes(StandardCharsets.UTF_8));
            out.writeByte(0);
            out.writeByte(0);
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.writeByte(0);
            Signature ecdsa = Signature.getInstance("SHA256withECDSAinP1363Format");
            ecdsa.initSign(key);
            ecdsa.update(content.toByteArray());
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            DataOutputStream headerOut = new DataOutputStream(header);
            headerOut.writeInt(1);
            headerOut.writeLong(stamp);
            headerOut.write(ecdsa.sign());
            return Base64.getEncoder().encodeToString(header.toByteArray());
        } catch (IOException | GeneralSecurityException e) {
            throw new AccountException("Couldn't sign the Xbox Live request", e);
        }
    }

    private static JsonObject jwk(ECPublicKey key) {
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kty", "EC");
        jwk.addProperty("alg", "ES256");
        jwk.addProperty("crv", "P-256");
        jwk.addProperty("use", "sig");
        jwk.addProperty("x", coordinate(key.getW().getAffineX()));
        jwk.addProperty("y", coordinate(key.getW().getAffineY()));
        return jwk;
    }

    private static String coordinate(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(raw.length, fixed.length);
        System.arraycopy(raw, raw.length - length, fixed, fixed.length - length, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    private static String liveError(AuthHttp.Reply reply) {
        String description = reply.text("error_description");
        if (description != null) {
            return description;
        }

        String error = reply.text("error");
        return error != null ? error : "HTTP " + reply.status();
    }

    private static String servicesError(AuthHttp.Reply reply) {
        String message = reply.text("errorMessage");
        return message != null ? message : "HTTP " + reply.status();
    }

    private static AccountException xboxError(AuthHttp.Reply reply) {
        String code = reply.text("XErr");
        if (code == null) {
            code = reply.headers().firstValue("X-Err").orElse(null);
        }

        if (code == null) {
            return new AccountException("Xbox Live sign-in failed (HTTP " + reply.status() + ")");
        }

        long xerr;
        try {
            xerr = Long.parseLong(code);
        } catch (NumberFormatException e) {
            return new AccountException("Xbox Live sign-in failed (" + code + ")");
        }

        return new AccountException(switch ((int) xerr) {
            case -2146051069 -> "This account is banned from Xbox Live";
            case -2146051063 -> "This Microsoft account has no Xbox profile yet - create one at xbox.com, then try again";
            case -2146051061 -> "Xbox Live isn't available in this account's country";
            case -2146051060 -> "This account needs age verification at account.microsoft.com first";
            case -2146051059 -> "This account has used up its allowed playtime for now";
            case -2146051058 -> "This is a child account - an adult has to add it to a Microsoft family first";
            default -> "Xbox Live refused the sign-in (error 0x" + Long.toHexString(xerr).toUpperCase(Locale.ROOT) + ")";
        });
    }

    @Environment(EnvType.CLIENT)
    record DeviceCode(String deviceCode, String userCode, String verificationUri, long intervalMs, long expiresAtMs) {
        String directUri() {
            return this.verificationUri + "?otc=" + this.userCode;
        }
    }

    @Environment(EnvType.CLIENT)
    record MsaToken(String accessToken, String refreshToken) {
    }
}
