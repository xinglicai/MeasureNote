package com.meownote.measure;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private ListView list;
    private TextView empty;
    private final List<Project> data = new ArrayList<>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        Button fab = findViewById(R.id.fab);

        adapter = new Adapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Project p = data.get(position);
                Intent i = new Intent(MainActivity.this, ProjectActivity.class);
                i.putExtra("projectId", p.id);
                startActivity(i);
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showProjectMenu(data.get(position));
                return true;
            }
        });

        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newProject();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(Store.listAll(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(data.isEmpty() ? View.GONE : View.VISIBLE);
        if (data.isEmpty()) empty.setText(R.string.no_projects);
    }

    private void newProject() {
        final EditText et = new EditText(this);
        et.setHint(R.string.project_name);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_project)
                .setView(et)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) name = "项目 " + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date());
                        Project p = new Project();
                        p.name = name;
                        try {
                            Store.save(MainActivity.this, p);
                            reload();
                            Intent i = new Intent(MainActivity.this, ProjectActivity.class);
                            i.putExtra("projectId", p.id);
                            startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "创建失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .show();
    }

    private void showProjectMenu(final Project p) {
        String[] items = {"重命名", "删除项目", "用当前单位换算全部标注"};
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) rename(p);
                        else if (which == 1) confirmDelete(p);
                        else convertAll(p);
                    }
                })
                .show();
    }

    private void rename(final Project p) {
        final EditText et = new EditText(this);
        et.setText(p.name);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() > 0) {
                            p.name = n;
                            try {
                                Store.save(MainActivity.this, p);
                                reload();
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final Project p) {
        new AlertDialog.Builder(this)
                .setTitle("删除项目")
                .setMessage("确定删除「" + p.name + "」？所有照片和标注都会一起删除，无法恢复。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Store.deleteProject(MainActivity.this, p.id);
                        reload();
                    }
                })
                .show();
    }

    /** 把项目内所有标注数值换算到项目当前单位 */
    private void convertAll(Project p) {
        final String[] labels = UnitUtil.LABELS;
        final String[] units = UnitUtil.UNITS;
        new AlertDialog.Builder(this)
                .setTitle("统一换算到单位")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String to = units[which];
                        int n = 0;
                        for (Project.Item it : p.items) {
                            for (Project.Ann a : it.anns) {
                                if (a.type != Project.Ann.TYPE_LINE) continue;
                                if (a.unit == null || a.unit.length() == 0) continue;
                                a.value = UnitUtil.convert(a.value, a.unit, to);
                                a.unit = to;
                                n++;
                            }
                        }
                        p.unit = to;
                        try {
                            Store.save(MainActivity.this, p);
                            Toast.makeText(MainActivity.this, "已换算 " + n + " 条标注 → " + to, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    /* ---------------- adapter ---------------- */

    private class Adapter extends BaseAdapter {
        private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int i) {
            return data.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            if (cv == null) {
                cv = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_project, parent, false);
            }
            Project p = data.get(i);
            TextView initial = cv.findViewById(R.id.initial);
            TextView name = cv.findViewById(R.id.name);
            TextView sub = cv.findViewById(R.id.sub);

            String nm = p.name == null ? "?" : p.name;
            initial.setText(nm.length() > 0 ? nm.substring(0, 1) : "?");
            name.setText(nm);
            sub.setText(p.items.size() + " 张照片 · " + p.unit + " · " + fmt.format(new Date(p.createdAt)));
            return cv;
        }
    }
}
