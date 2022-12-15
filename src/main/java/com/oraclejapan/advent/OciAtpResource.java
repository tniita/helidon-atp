
package com.oraclejapan.advent;


import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.oracle.bmc.database.Database;
import com.oracle.bmc.database.model.GenerateAutonomousDatabaseWalletDetails;
import com.oracle.bmc.database.requests.GenerateAutonomousDatabaseWalletRequest;
import com.oracle.bmc.database.responses.GenerateAutonomousDatabaseWalletResponse;
import com.oracle.bmc.http.internal.ResponseHelper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import oracle.security.pki.OraclePKIProvider;
import oracle.ucp.jdbc.PoolDataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;


@Path("/atp")
public class OciAtpResource {
    private static final Logger LOGGER = Logger.getLogger(OciAtpResource.class.getName());

    private final Database databaseClient;
    private final PoolDataSource atpDataSource;
    private final String atpTnsNetServiceName;

    private final String atpOcid;
    private final String walletPassword;

    @Inject
    OciAtpResource(Database databaseClient, @Named("atp") PoolDataSource atpDataSource,
                @ConfigProperty(name = "oracle.ucp.jdbc.PoolDataSource.atp.tnsNetServiceName") String atpTnsNetServiceName,
                @ConfigProperty(name = "oci.atp.ocid") String atpOcid,
                @ConfigProperty(name = "oci.atp.walletPassword") String walletPassword) {
        this.databaseClient = databaseClient;
        this.atpDataSource = Objects.requireNonNull(atpDataSource);
        this.atpTnsNetServiceName = atpTnsNetServiceName;
        this.atpOcid = atpOcid;
        this.walletPassword = walletPassword;
    }

    /**
     * Walletを作成してDBに接続し結果を返す
     *
     * @return Response
     */
    @GET
    @Path("/wallet")
    public Response generateWallet() {
        ResponseHelper.shouldAutoCloseResponseInputStream(false);
        GenerateAutonomousDatabaseWalletResponse walletResponse =
                databaseClient.generateAutonomousDatabaseWallet(
                        GenerateAutonomousDatabaseWalletRequest.builder()
                                .autonomousDatabaseId(this.atpOcid)
                                .generateAutonomousDatabaseWalletDetails(
                                        GenerateAutonomousDatabaseWalletDetails.builder()
                                                .password(this.walletPassword)
                                                .build())
                                .build());

        if (walletResponse.getContentLength() == 0) {
            LOGGER.log(Level.SEVERE, "GenerateAutonomousDatabaseWalletResponse is empty");
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        byte[] walletContent = null;
        try {
            walletContent = walletResponse.getInputStream().readAllBytes();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error processing GenerateAutonomousDatabaseWalletResponse", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        String returnEntity = null;
        try {
            this.atpDataSource.setSSLContext(getSSLContext(walletContent));
            this.atpDataSource.setURL(getJdbcUrl(walletContent, this.atpTnsNetServiceName));
            try (
                    Connection connection = this.atpDataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement("SELECT 'Hello world!!' FROM DUAL");
                    ResultSet rs = ps.executeQuery()
            ){
                rs.next();
                returnEntity = rs.getString(1);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error setting up DataSource", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        return Response.status(Response.Status.OK).entity(returnEntity).build();
    }

    /**
     * SSLContextを生成
     *
     * @param walletContent
     * @return SSLContext
     */
    private static SSLContext getSSLContext(byte[] walletContent) throws IllegalStateException {
        SSLContext sslContext = null;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new ByteArrayInputStream(walletContent)))) {
            ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("cwallet.sso")) {
                    KeyStore keyStore = KeyStore.getInstance("SSO", new OraclePKIProvider());
                    keyStore.load(zis, null);
                    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
                    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("PKIX");
                    trustManagerFactory.init(keyStore);
                    keyManagerFactory.init(keyStore, null);
                    sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
                }
                zis.closeEntry();
            }
        } catch (RuntimeException | Error throwMe) {
            throw throwMe;
        } catch (Exception e) {
            throw new IllegalStateException("Error while getting SSLContext from wallet.", e);
        }
        return sslContext;
    }

    /**
     * 接続識別子の生成
     *
     * @param walletContent
     * @param tnsNetServiceName
     * @return String
     */
    private static String getJdbcUrl(byte[] walletContent, String tnsNetServiceName) throws IllegalStateException {
        String jdbcUrl = null;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new ByteArrayInputStream(walletContent)))) {
            ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("tnsnames.ora")) {
                    jdbcUrl = new String(zis.readAllBytes(), StandardCharsets.UTF_8)
                            .replaceFirst(tnsNetServiceName + "\\s*=\\s*", "jdbc:oracle:thin:@")
                            .replaceAll("\\n[^\\n]+", "");
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Error while getting JDBC URL from wallet.", e);
        }
        return jdbcUrl;
    }
}