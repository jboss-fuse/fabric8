package com.redhat.gss.redhat_support_lib.infrastructure;

import java.io.File;
import java.util.List;

import javax.ws.rs.core.Response;

import com.redhat.gss.redhat_support_lib.parsers.ExtractedSymptom;
import com.redhat.gss.redhat_support_lib.web.ConnectionManager;

public class Symptoms extends BaseQuery {
    ConnectionManager connectionManager = null;
	static String url = "/rs/symptoms/extractor/";

	public Symptoms(ConnectionManager connectionManager) {
		this.connectionManager = connectionManager;
	}

	/**
	 * Queries the problems RESTful interface with a given file. RESTful method:
	 * https://api.access.redhat.com/rs/problems
	 * 
	 * @param fileName
	 *            File name whose content you want diagnosed.
	 * @return An array of problems.
	 * @throws Exception 
	 */
	public List<ExtractedSymptom> retrieveSymptoms(String fileName) throws Exception {
		String fullUrl = connectionManager.getConfig().getUrl() + url;

		Response response = upload(connectionManager.getConnection(), fullUrl, new File(fileName), fileName);
		com.redhat.gss.redhat_support_lib.parsers.ExtractedSymptoms symptoms = response
				.readEntity(com.redhat.gss.redhat_support_lib.parsers.ExtractedSymptoms.class);
		return symptoms.getExtractedSymptom();
	}

}
