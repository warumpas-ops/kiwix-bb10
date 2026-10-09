package com.kiwixbb10;

import android.app.Activity;
import android.app.AlertDialog;
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

        mAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, mDisplayNames) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v.findViewById(android.R.id.text1))
                        .setTextColor(0xFFEEEEEE);
                v.setBackgroundColor(position % 2 == 0 ? 0xFF1a1a2e : 0xFF16213e);
                return v;
            }
        };
        mListView.setAdapter(mAdapter);
        mListView.setBackgroundColor(0xFF1a1a2e);

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

        scanForZimFiles();
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
