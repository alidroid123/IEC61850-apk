package com.alidev.dfrtools.dfr;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alidev.dfrtools.R;

import java.util.Collections;
import java.util.List;

/** Read-only table of "d" (description) attributes fetched via MMS Explorer's "Get Definition" - see NodeDefinitionManager. */
public class NodeDefinitionListActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_definitions);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        List<NodeDefinition> items = new NodeDefinitionManager(this).getAll();
        Collections.sort(items, (a, b) -> {
            int byName = a.deviceName.compareToIgnoreCase(b.deviceName);
            return byName != 0 ? byName : a.nodeAddress.compareToIgnoreCase(b.nodeAddress);
        });

        View layoutEmpty = findViewById(R.id.layoutDefinitionsEmpty);
        layoutEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);

        RecyclerView rv = findViewById(R.id.rvDefinitions);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new RecyclerView.Adapter<DefVH>() {
            @NonNull @Override
            public DefVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new DefVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_node_definition_row, parent, false));
            }

            @Override public void onBindViewHolder(@NonNull DefVH holder, int position) {
                holder.bind(items.get(position));
            }

            @Override public int getItemCount() { return items.size(); }
        });
    }

    static class DefVH extends RecyclerView.ViewHolder {
        final TextView txtName, txtAddress, txtValue;

        DefVH(@NonNull View v) {
            super(v);
            txtName = v.findViewById(R.id.txtDefName);
            txtAddress = v.findViewById(R.id.txtDefAddress);
            txtValue = v.findViewById(R.id.txtDefValue);
        }

        void bind(NodeDefinition d) {
            txtName.setText(d.deviceName);
            txtAddress.setText(d.nodeAddress);
            txtValue.setText(d.value);
        }
    }
}
