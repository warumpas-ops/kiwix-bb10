package com.kiwixbb10;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Library screen: scans storage for .zim files and lets the user pick one.
 */
public class LibraryActivity extends Activity {
    private static final String TAG = "LibraryActivity";

    private ListView  mListView;
    private TextView  mTextEmpty;
    private Button    mBtnRefresh;

    private List<File> mZimFiles = new ArrayList<File>();
    private ArrayAdapter<String> mAdapter;
    private List<String> mDisplayNames = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        mListView  = (ListView)  findViewById(R.id.list_zim_files);
        mTextEmpty = (TextView)  findViewById(R.id.text_empty);
        mBtnRefresh= (Button)    findViewById(R.id.btn_refresh);
        Button btnGetBooks = (Button) findViewById(R.id.btn_get_books);

        mAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, mDisplayNames) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v.findViewById(android.R.id.text1))
                        .setTextColor(0xFFEEEEEE);
                v.setBackgroundColor(position % 2 == 0 ? 0xFF181818 : 0xFF222222);
                return v;
            }
        };
        mListView.setAdapter(mAdapter);
        mListView.setBackgroundColor(0xFF121212);

        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openZimFile(mZimFiles.get(position));
            }
        });

        mBtnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanForZimFiles();
            }
        });

        if (btnGetBooks != null) {
            btnGetBooks.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCatalogDialog();
                }
            });
        }

        scanForZimFiles();
    }

    private void showCatalogDialog() {
        final String[] items = {
            "Open Kiwix Library (library.kiwix.org)",
            "English Wikipedia (Mini / Top Articles)",
            "German Wikipedia (Mini / Top Articles)",
            "French Wikipedia (Mini / Top Articles)",
            "Wiktionary (English Dictionary)",
            "WikiMed (Medical Encyclopedia)",
            "Project Gutenberg (Classic Books)"
        };
        final String[] urls = {
            "https://library.kiwix.org",
            "https://download.kiwix.org/zim/wikipedia/",
            "https://download.kiwix.org/zim/wikipedia/",
            "https://download.kiwix.org/zim/wikipedia/",
            "https://download.kiwix.org/zim/wiktionary/",
            "https://download.kiwix.org/zim/wikimed/",
            "https://download.kiwix.org/zim/gutenberg/"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Get Offline Content");
        builder.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(android.net.Uri.parse(urls[which]));
                startActivity(i);
            }
        });
        builder.setNegativeButton("Close", null);
        builder.show();
    }

    private void scanForZimFiles() {
        mZimFiles.clear();
        mDisplayNames.clear();

        // Scan multiple locations
        List<File> searchRoots = new ArrayList<File>();
        searchRoots.add(Environment.getExternalStorageDirectory());
        searchRoots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        searchRoots.add(new File("/sdcard"));
        searchRoots.add(new File("/mnt/sdcard"));
        searchRoots.add(new File("/storage/sdcard0"));
        searchRoots.add(new File("/storage/emulated/0"));

        for (File root : searchRoots) {
            if (root != null && root.exists()) {
                findZimFiles(root, 0);
            }
        }

        if (mZimFiles.isEmpty()) {
            mListView.setVisibility(View.GONE);
            mTextEmpty.setVisibility(View.VISIBLE);
        } else {
            mListView.setVisibility(View.VISIBLE);
            mTextEmpty.setVisibility(View.GONE);
        }

        mAdapter.notifyDataSetChanged();
        Toast.makeText(this, "Found " + mZimFiles.size() + " .zim file(s)", Toast.LENGTH_SHORT).show();
    }

    private void findZimFiles(File dir, int depth) {
        if (depth > 4 || dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".zim")) {
                // Deduplicate
                boolean dup = false;
                for (File existing : mZimFiles) {
                    if (existing.getAbsolutePath().equals(f.getAbsolutePath())) {
                        dup = true; break;
                    }
                }
                if (!dup) {
                    mZimFiles.add(f);
                    long mb = f.length() / (1024 * 1024);
                    mDisplayNames.add(f.getName() + "  [" + mb + " MB]\n" + f.getParent());
                }
            } else if (f.isDirectory() && !f.getName().startsWith(".")) {
                findZimFiles(f, depth + 1);
            }
        }
    }

    private void openZimFile(final File zimFile) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("zim_path", zimFile.getAbsolutePath());
        startActivity(intent);
    }
}
