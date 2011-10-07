package de.tub.citydb.gui.components.bbox;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.tub.citydb.api.config.BoundingBox;
import de.tub.citydb.api.config.DatabaseSrs;
import de.tub.citydb.config.Config;
import de.tub.citydb.log.Logger;

public class BoundingBoxClipboardHandler implements ClipboardOwner {
	private static final Logger LOG = Logger.getInstance();
	private static BoundingBoxClipboardHandler instance;

	private final Config config;
	private boolean isMac;
	private Clipboard systemClipboard;

	private BoundingBoxClipboardHandler(Config config) {
		// just to thwart instantiation
		this.config = config;
		isMac = System.getProperty("os.name").toLowerCase().contains("mac");
		systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
	}

	public static synchronized BoundingBoxClipboardHandler getInstance(Config config) {
		if (instance == null)
			instance = new BoundingBoxClipboardHandler(config);

		return instance;
	}

	public void putBoundingBox(BoundingBox bbox) {
		StringBuilder content = new StringBuilder();
		content.append("bbox=")
		.append(bbox.getLowerLeftCorner().getX()).append(",")
		.append(bbox.getLowerLeftCorner().getY()).append(",")
		.append(bbox.getUpperRightCorner().getX()).append(",")
		.append(bbox.getUpperRightCorner().getY());

		if (bbox.isSetSrs()) {
			content.append("&3dcitydb_srs=")
			.append(bbox.getSrs().getId());
		}

		systemClipboard.setContents(new StringSelection(content.toString()), this);
	}

	public BoundingBox getBoundingBox() {
		if (systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
			try {
				Transferable content = systemClipboard.getContents(null);
				String bbox = (String)content.getTransferData(DataFlavor.stringFlavor);

				BoundingBox result = parseWebServiceRepresentation(bbox);
				if (result != null)
					return result;

				result = parseGMLEnvelopeRepresentation(bbox);
				if (result != null)
					return result;

			} catch (Exception e) {
				//
			}
		}

		LOG.error("Failed to interpret clipboard content as bounding box.");
		return null;
	}

	public boolean containsPossibleBoundingBox() {
		return isMac ? true : systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor);
	}

	@Override
	public void lostOwnership(Clipboard clipboard, Transferable contents) {
		// not necessary now
	}

	private BoundingBox parseWebServiceRepresentation(String candidate) {	
		BoundingBox bbox = new BoundingBox();
		String[] tokens = candidate.trim().split("&+");

		for (String token : tokens) {
			String[] pair = token.trim().split("=");
			if (pair != null && pair.length == 2) {
				String key = pair[0].trim();
				String value = pair[1].trim();

				if (key != null && value != null && key.length() > 0 && value.length() > 0) {
					if ("bbox".equals(key.toLowerCase())) {
						String[] coords = value.split("[,|;|\\s]+");

						if (coords != null && coords.length == 4) {
							try {
								NumberFormat format = NumberFormat.getInstance(Locale.ENGLISH);
								bbox.getLowerLeftCorner().setX(format.parse(coords[0].trim()).doubleValue());
								bbox.getLowerLeftCorner().setY(format.parse(coords[1].trim()).doubleValue());
								bbox.getUpperRightCorner().setX(format.parse(coords[2].trim()).doubleValue());
								bbox.getUpperRightCorner().setY(format.parse(coords[3].trim()).doubleValue());
							} catch (Exception e) {
								//
							}
						}
					} else if ("3dcitydb_srs".equals(key.toLowerCase())) {
						for (DatabaseSrs srs : config.getProject().getDatabase().getReferenceSystems()) {
							if (value.equals(srs.getId())) {
								bbox.setSrs(srs);
								break;
							}
						}
					}
				}
			}
		}

		return bbox.isSetLowerLeftCorner() && bbox.isSetUpperRightCorner() ? bbox : null;
	}

	private BoundingBox parseGMLEnvelopeRepresentation(String candidate) {	
		String lowerCorner = "</?(.*?:)?lowerCorner>";
		String upperCorner = "</?(.*?:)?upperCorner>";

		StringBuilder regex = new StringBuilder();
		regex.append(".*?")
		.append(lowerCorner)
		.append("(.*?)")
		.append(lowerCorner)
		.append("(.*?)")
		.append(upperCorner)
		.append("(.*?)")
		.append(upperCorner)
		.append(".*?");

		Pattern pattern = Pattern.compile(regex.toString(), Pattern.MULTILINE | Pattern.DOTALL);
		Matcher matcher = pattern.matcher(candidate.trim());

		if (matcher.matches()) {
			try {
				NumberFormat format = NumberFormat.getInstance(Locale.ENGLISH);	

				String lowerCornerCoords = matcher.group(2);
				String upperCornerCoords = matcher.group(6);

				String value = "([-|\\+]?\\d*?(\\.\\d+?)??)";			

				regex = new StringBuilder();
				regex.append("\\s*").append(value)
				.append("\\s+").append(value)
				.append("(\\s+").append(value).append(")?")
				.append("\\s*");

				pattern = Pattern.compile(regex.toString(), Pattern.MULTILINE | Pattern.DOTALL);
				Matcher lower = pattern.matcher(lowerCornerCoords.trim());
				Matcher upper = pattern.matcher(upperCornerCoords.trim());

				if (lower.matches() && upper.matches()) {
					BoundingBox bbox = new BoundingBox();

					bbox.getLowerLeftCorner().setX(format.parse(lower.group(1)).doubleValue());
					bbox.getLowerLeftCorner().setY(format.parse(lower.group(3)).doubleValue());
					bbox.getUpperRightCorner().setX(format.parse(upper.group(1)).doubleValue());
					bbox.getUpperRightCorner().setY(format.parse(upper.group(3)).doubleValue());

					return bbox;
				}
			} catch (Exception e) {
				//
			}
		}

		return null;
	}

}