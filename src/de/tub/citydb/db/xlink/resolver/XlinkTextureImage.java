package de.tub.citydb.db.xlink.resolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleResultSet;
import oracle.ord.im.OrdImage;
import de.tub.citydb.config.Config;
import de.tub.citydb.db.xlink.DBXlinkExternalFile;
import de.tub.citydb.event.info.LogMessageEnum;
import de.tub.citydb.event.info.LogMessageEvent;
import de.tub.citydb.event.statistic.TextureImageCountEvent;

public class XlinkTextureImage implements DBXlinkResolver {
	private final Connection externalFileConn;
	private final Config config;
	private final DBXlinkResolverManager resolverManager;

	private PreparedStatement psPrepare;
	PreparedStatement psSelect;
	OraclePreparedStatement psInsert;
	private String localPath;
	private TextureImageCountEvent counter;

	public XlinkTextureImage(Connection externalFileConn, Config config, DBXlinkResolverManager resolverManager) throws SQLException {
		this.externalFileConn = externalFileConn;
		this.config = config;
		this.resolverManager = resolverManager;

		init();
	}

	private void init() throws SQLException {
		localPath = config.getInternal().getImportPath();
		counter = new TextureImageCountEvent(1);
		
		psPrepare = externalFileConn.prepareStatement("update SURFACE_DATA set TEX_IMAGE=ordimage.init() where ID=?");
		psSelect = externalFileConn.prepareStatement("select TEX_IMAGE from SURFACE_DATA where ID=? for update");
		psInsert = (OraclePreparedStatement)externalFileConn.prepareStatement("update SURFACE_DATA set TEX_IMAGE=? where ID=?");
	}

	public boolean insert(DBXlinkExternalFile xlink) throws SQLException {
		String imageFileName = xlink.getFileURI();
		boolean isRemote = true;
		URL imageURL = null;
		try {
			// first step: prepare ORDIMAGE
			psPrepare.setLong(1, xlink.getId());
			psPrepare.executeUpdate();

			// second step: get prepared ORDIMAGE to fill it with contents
			psSelect.setLong(1, xlink.getId());
			OracleResultSet rs = (OracleResultSet)psSelect.executeQuery();
			if (!rs.next()) {
				LogMessageEvent log = new LogMessageEvent(
						"Datenbank-Fehler beim Import einer Texturdatei aufgetreten.",
						LogMessageEnum.ERROR);
				resolverManager.propagateEvent(log);

				externalFileConn.rollback();
				return false;
			}

			OrdImage imgProxy = (OrdImage)rs.getORAData(1, OrdImage.getORADataFactory());

			// third step: try and upload image data
			try {
				imageURL = new URL(imageFileName);
				imageFileName = imageURL.toString();

			} catch (MalformedURLException malURL) {
				isRemote = false;
				File imageFile = new File(imageFileName);
				imageFileName = localPath + File.separator + imageFile.getPath();
			}

			resolverManager.propagateEvent(new LogMessageEvent(
					"Importiere Texturdatei: " + imageFileName,
					LogMessageEnum.DEBUG));

			resolverManager.propagateEvent(counter);
			
			boolean letDBdetermineProperties = true;

			if (isRemote) {
				InputStream stream = imageURL.openStream();
				imgProxy.loadDataFromInputStream(stream);
			} else {
				imgProxy.loadDataFromFile(imageFileName);

				// determing image formats by file extension
				int index = imageFileName.lastIndexOf('.');
				if (index != -1) {
					String extension = imageFileName.substring(index + 1, imageFileName.length());

					if (extension.toUpperCase().equals("RGB")) {
						imgProxy.setMimeType("image/rgb");
						imgProxy.setFormat("RGB");
						imgProxy.setContentLength(1);

						letDBdetermineProperties = false;
					}
				}
			}

			if (letDBdetermineProperties)
				imgProxy.setProperties();

			psInsert.setORAData(1, imgProxy);
			psInsert.setLong(2, xlink.getId());
			psInsert.execute();

			externalFileConn.commit();

		} catch (IOException ioEx) {
			LogMessageEvent log = new LogMessageEvent(
					"Lesefehler bei " + imageFileName + ": " + ioEx,
					LogMessageEnum.ERROR);
			resolverManager.propagateEvent(log);

			externalFileConn.rollback();
			return false;
		} catch (SQLException sqlEx) {
			LogMessageEvent log = new LogMessageEvent(
					"SQL-Fehler bei " + imageFileName + ": " + sqlEx,
					LogMessageEnum.ERROR);
			resolverManager.propagateEvent(log);

			externalFileConn.rollback();
			return false;
		}

		return true;
	}

	@Override
	public void executeBatch() throws SQLException {
		// we do not have any action here, since we are heavily committing and roll-backing
		// within the insert-method. that's also the reason why we need a separated connection instance.
	}

	@Override
	public DBXlinkResolverEnum getDBXlinkResolverType() {
		return DBXlinkResolverEnum.TEXTURE_IMAGE;
	}

}
