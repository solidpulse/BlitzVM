package dev.phpruntime;

import com.oracle.truffle.api.TruffleFile;

import java.nio.charset.Charset;

public final class PhpFileDetector implements TruffleFile.FileTypeDetector {

    @Override
    public String findMimeType(TruffleFile file) {
        String name = file.getName();
        return name != null && name.endsWith(".php") ? PhpLanguage.MIME_TYPE : null;
    }

    @Override
    public Charset findEncoding(TruffleFile file) {
        return null;
    }
}
