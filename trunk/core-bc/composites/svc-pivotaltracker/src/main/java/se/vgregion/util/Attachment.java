package se.vgregion.util;

import java.io.InputStream;

public class Attachment {

    private String filename;

    private InputStream data;

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public InputStream getData() {
        return data;
    }

    public void setData(InputStream data) {
        this.data = data;
    }
}