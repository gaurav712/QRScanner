package com.fuzz.qrscanner;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartsheet.api.Smartsheet;
import com.smartsheet.api.SmartsheetBuilder;
import com.smartsheet.api.SmartsheetException;
import com.smartsheet.api.internal.http.AndroidHttpClient;
import com.smartsheet.api.models.Cell;
import com.smartsheet.api.models.Column;
import com.smartsheet.api.models.PagedResult;
import com.smartsheet.api.models.PaginationParameters;
import com.smartsheet.api.models.Row;
import com.smartsheet.api.models.enums.ColumnInclusion;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

public class SmartsheetAPI {
    // Static vars
    private static final String ENCODING_UTF8 = "UTF-8";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Column>> TYPE_LIST_COLUMN = new TypeReference<List<Column>>(){};
    public static final String LOG_TAG = "QRContact";

    private static File getCacheDir(Context context, boolean create) {
        File cacheDir = new File(context.getApplicationInfo().dataDir + "/cache");
        if (create && !cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir;
    }

    public static boolean clearCache(Context context) {
        File cacheDir = getCacheDir(context, false);
        if (cacheDir.exists()) {
            try {
                FileUtils.deleteDirectory(cacheDir);
            } catch (IOException ex) {
                Log.d(LOG_TAG, "Error deleting cache dir.", ex);
                return false;
            }
        }
        return true;
    }

    // Member vars
    private final Context context;
    private final Smartsheet client;

    public SmartsheetAPI(Context context, String token) {
        this.context = context;
        this.client = new SmartsheetBuilder().setAccessToken(token).setHttpClient(new AndroidHttpClient()).build();
    }

    private List<Column> getColumns(long sheetID) throws SmartsheetException {
        // Read from cache if we can
        FileCache cache = new FileCache(this.context, "column-" + sheetID + ".json");
        List<Column> columns = null;
        try {
            String json = cache.load();
            if (json != null) {
                columns = OBJECT_MAPPER.readValue(json, TYPE_LIST_COLUMN);
            }
        } catch (IOException ex) {
            Log.d(LOG_TAG, "Error loading from cache.", ex);
        }

        // If we did not load from cache call API.
        if (columns == null) {
            Log.d(LOG_TAG, "Loading columns from Smartsheet API.");
            PaginationParameters parameters = new PaginationParameters.PaginationParametersBuilder().setIncludeAll(true).build();
            PagedResult<Column> wrapper = this.client.sheetResources().columnResources().listColumns(sheetID, EnumSet.allOf(ColumnInclusion.class), parameters);
            columns = wrapper.getData();

            // save to cache
            try {
                cache.save(OBJECT_MAPPER.writeValueAsString(columns));
            } catch (IOException ex) {
                Log.d(LOG_TAG, "Error saving to cache.", ex);
            }
        } else {
            Log.d(LOG_TAG, "Loaded columns from cache.");
        }
        return columns;
    }

    public void saveContact(long sheetID, QRContact contact) throws SmartsheetException {
        List<Column> columns = getColumns(sheetID);

        // Iterate through or columns and see if we can find matches
        Cell.AddRowCellsBuilder builder = new Cell.AddRowCellsBuilder();
        for (Column column : columns) {
            switch(column.getTitle().toLowerCase()) {
                case "name":
                    builder.addCell(column.getId(), contact.name);
                    break;
                case "email":
                    builder.addCell(column.getId(), contact.email);
                    break;
                case "org":
                case "organization":
                    builder.addCell(column.getId(), contact.org);
                    break;
                case "title":
                    builder.addCell(column.getId(), contact.title);
                    break;
                case "raw":
                case "qrcode":
                case "rawqrcode":
                    builder.addCell(column.getId(), contact.rawQRCode);
                    break;
            }
        }

        // Save our row if we found any match
        List<Cell> cells = builder.build();
        if (cells != null && cells.size() > 0) {
            Row row = new Row.AddRowBuilder().setCells(cells).setToBottom(true).build();
            this.client.sheetResources().rowResources().addRows(sheetID, Arrays.asList(row));
        }
    }

    /**
     * Simple wrapper around a cache file we can store temporary info in.
     */
    private class FileCache {

        private final Context context;
        private final String fileName;

        private FileCache(Context context, String fileName) {
            this.context = context;
            this.fileName = fileName;
        }

        private File getCacheFile() throws IOException {
            File cacheDir = getCacheDir(this.context, true);
            return new File(cacheDir, this.fileName);
        }

        private void save(String json) throws IOException {
            File cacheFile = getCacheFile();
            FileUtils.writeStringToFile(cacheFile, json, ENCODING_UTF8);
        }

        private String load() throws IOException {
            File cacheFile = getCacheFile();
            if (cacheFile.exists()) {
                return FileUtils.readFileToString(cacheFile, ENCODING_UTF8);
            }
            return null;
        }
    }
}
