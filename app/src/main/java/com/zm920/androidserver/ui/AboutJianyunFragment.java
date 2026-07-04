package com.zm920.androidserver.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.zm920.androidserver.R;

public class AboutJianyunFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about_jianyun, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 返回按钮
        view.findViewById(R.id.btn_back).setOnClickListener(v -> {
            requireActivity().getSupportFragmentManager().popBackStack();
        });

        // 各合规板块点击展开/收起
        setupSection(view, R.id.section_about, R.id.content_about);
        setupSection(view, R.id.section_beian, R.id.content_beian);
        setupSection(view, R.id.section_privacy, R.id.content_privacy);
        setupSection(view, R.id.section_permission, R.id.content_permission);
        setupSection(view, R.id.section_disclaimer, R.id.content_disclaimer);
        setupSection(view, R.id.section_content, R.id.content_content);
    }

    private void setupSection(View root, int headerId, int contentId) {
        View header = root.findViewById(headerId);
        final View content = root.findViewById(contentId);
        if (header != null && content != null) {
            content.setVisibility(View.GONE);
            header.setOnClickListener(v -> {
                if (content.getVisibility() == View.GONE) {
                    content.setVisibility(View.VISIBLE);
                } else {
                    content.setVisibility(View.GONE);
                }
            });
        }
    }
}
