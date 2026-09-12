package com.omniforge.ui.enterprise;

import com.omniforge.ui.Toast;
import com.omniforge.ui.enterprise.EnterpriseAdminAccess.CapabilityRow;
import com.omniforge.ui.enterprise.EnterpriseAdminAccess.DepartmentRow;
import com.omniforge.ui.enterprise.EnterpriseAdminAccess.RoleDraft;
import com.omniforge.ui.enterprise.EnterpriseAdminAccess.RoleRow;
import com.omniforge.ui.enterprise.EnterpriseAdminAccess.UserRow;
import com.omniforge.ui.enterprise.EnterpriseBridge.AuthExpiredException;
import com.omniforge.ui.theme.ThemeManager;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 企业版管理对话框（P2-2，用户/部门/角色三 Tab，仅 role=admin 显示入口；
 * 服务端强校验 role=admin）。所有 IO 在虚拟线程执行，登录态失效 → 关框回调重登。
 */
public final class EnterpriseAdminDialog {

    private static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EnterpriseAdminAccess admin;
    private final Runnable onAuthExpired;

    private final Stage stage = new Stage();
    private final Label usersPageLabel = new Label();
    private final Button prevPageButton = new Button("◀ 上一页");
    private final Button nextPageButton = new Button("下一页 ▶");
    private final TextField keywordField = new TextField();
    private final TableView<UserRow> usersTable = new TableView<>();
    private final TableView<DepartmentRow> deptTable = new TableView<>();
    private final TableView<RoleRow> roleTable = new TableView<>();
    private final TableView<EnterpriseAdminAccess.QuotaSummary.UserUsage> quotaUserTable = new TableView<>();
    private final TableView<EnterpriseAdminAccess.QuotaSummary.DepartmentUsage> quotaDeptTable = new TableView<>();
    private final TableView<EnterpriseAdminAccess.QuotaSummary.ModelUsage> quotaModelTable = new TableView<>();
    private final Label quotaTotalLabel = new Label();
    private final javafx.scene.control.TextField quotaBudgetField = new javafx.scene.control.TextField();
    private final javafx.scene.control.TextField quotaPerUserField = new javafx.scene.control.TextField();
    /** 模型级月限额（C2 v1.1：alias → 限额行；用量页加载时刷新） */
    private final java.util.Map<String, EnterpriseAdminAccess.QuotaLimits.ModelLimit> modelLimitMap =
            new java.util.HashMap<>();

    private int userPage = 0;
    private List<DepartmentRow> departmentsCache = new ArrayList<>();
    private List<RoleRow> rolesCache = new ArrayList<>();
    private List<CapabilityRow> capabilitiesCache = new ArrayList<>();

    private EnterpriseAdminDialog(EnterpriseAdminAccess admin, Runnable onAuthExpired) {
        this.admin = admin;
        this.onAuthExpired = onAuthExpired;
    }

    public static void show(Stage owner, EnterpriseAdminAccess admin, Runnable onAuthExpired) {
        new EnterpriseAdminDialog(admin, onAuthExpired).open(owner);
    }

    private void open(Stage owner) {
        stage.setTitle("🏛 管理（用户 / 部门 / 角色）");
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setScene(new Scene(buildRoot(), 1000, 620));
        ThemeManager.attach(stage.getScene());
        refreshAll();
        stage.show();
    }

    private VBox buildRoot() {
        TabPane tabs = new TabPane(
                new Tab("用户", buildUsersPane()),
                new Tab("部门", buildDepartmentsPane()),
                new Tab("角色", buildRolesPane()),
                new Tab("用量", buildQuotaPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox box = new VBox(8, tabs);
        box.setPadding(new Insets(10));
        return box;
    }

    // ---------------- 异步 IO 通用 ----------------

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws Exception;
    }

    private <T> void async(IoAction<T> io, java.util.function.Consumer<T> onSuccess) {
        Thread.ofVirtual().start(() -> {
            try {
                T result = io.run();
                Platform.runLater(() -> {
                    try {
                        onSuccess.accept(result);
                    } catch (Exception e) {
                        Toast.show(null, "操作失败：" + message(e), false);
                    }
                });
            } catch (AuthExpiredException e) {
                Platform.runLater(() -> {
                    stage.close();
                    onAuthExpired.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> Toast.show(null, "操作失败：" + message(e), false));
            }
        });
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    // ---------------- 用户 Tab ----------------

    private VBox buildUsersPane() {
        keywordField.setPromptText("搜索用户名 / 姓名…");
        keywordField.setPrefWidth(190);
        Button searchButton = new Button("搜索");
        Button refreshButton = new Button("刷新");
        Button newUserButton = new Button("＋ 新建用户");
        newUserButton.getStyleClass().add("primary");
        usersPageLabel.getStyleClass().add("status");

        usersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        usersTable.setPlaceholder(new Label("暂无用户"));
        usersTable.getColumns().addAll(
                col("用户名", 110, UserRow::username),
                col("姓名", 90, UserRow::name),
                col("手机号", 110, UserRow::mobile),
                col("部门", 110, UserRow::departmentName),
                col("角色", 90, UserRow::role),
                col("状态", 60, row -> row.enabled() ? "启用" : "停用"),
                col("创建时间", 130, row -> row.createdAt() == null ? "" : TIME.format(row.createdAt())),
                actionColumn("操作", 150, row -> {
                    Button edit = new Button("编辑");
                    edit.setOnAction(e -> editUser(row));
                    Button del = new Button("删除");
                    del.getStyleClass().add("danger");
                    del.setOnAction(e -> deleteUser(row));
                    return new HBox(6, edit, del);
                }));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, keywordField, searchButton, refreshButton, newUserButton,
                spacer, usersPageLabel, prevPageButton, nextPageButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        searchButton.setOnAction(e -> loadUsers(0));
        refreshButton.setOnAction(e -> loadUsers(userPage));
        keywordField.setOnAction(e -> loadUsers(0));
        prevPageButton.setOnAction(e -> loadUsers(userPage - 1));
        nextPageButton.setOnAction(e -> loadUsers(userPage + 1));
        newUserButton.setOnAction(e -> userDialog(null));

        VBox pane = new VBox(8, toolbar, usersTable);
        VBox.setVgrow(usersTable, Priority.ALWAYS);
        return pane;
    }

    private void loadUsers(int page) {
        String keyword = keywordField.getText() == null ? "" : keywordField.getText().trim();
        async(() -> admin.users(keyword.isEmpty() ? null : keyword, null, null, null,
                Math.max(0, page), PAGE_SIZE), paged -> {
            int totalPages = (int) Math.max(1, (paged.total() + PAGE_SIZE - 1) / PAGE_SIZE);
            userPage = Math.min(Math.max(0, page), totalPages - 1);
            usersTable.getItems().setAll(paged.items());
            usersPageLabel.setText("第 " + (userPage + 1) + "/" + totalPages
                    + " 页 · 共 " + paged.total() + " 个用户");
            prevPageButton.setDisable(userPage <= 0);
            nextPageButton.setDisable(userPage >= totalPages - 1);
        });
    }

    private void userDialog(UserRow row) {
        boolean creating = row == null;
        Dialog<SavedUser> dialog = new Dialog<>();
        dialog.setTitle(creating ? "新建用户" : "编辑用户「" + row.username() + "」");
        dialog.initOwner(stage);

        TextField usernameField = new TextField(creating ? "" : row.username());
        usernameField.setDisable(!creating);
        usernameField.setPromptText("登录用户名（不可改）");
        TextField nameField = new TextField(creating ? "" : nullToEmpty(row.name()));
        TextField mobileField = new TextField(creating ? "" : nullToEmpty(row.mobile()));
        ComboBox<String> deptCombo = new ComboBox<>();
        deptCombo.getItems().add("（未分配部门）");
        departmentsCache.forEach(d -> deptCombo.getItems().add(d.name()));
        deptCombo.getSelectionModel().select(creating ? 0
                : row.departmentName() == null ? 0 : Math.max(0, indexOfName(deptCombo, row.departmentName())));
        ComboBox<String> roleCombo = new ComboBox<>();
        rolesCache.forEach(r -> roleCombo.getItems().add(r.name()));
        if (creating) {
            roleCombo.getSelectionModel().selectFirst();
        } else if (roleCombo.getItems().contains(row.role())) {
            roleCombo.getSelectionModel().select(row.role());
        }
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(creating ? "初始密码（≥6 位）" : "（不修改密码）");
        CheckBox enabledBox = new CheckBox("启用账号");
        enabledBox.setSelected(creating || row.enabled());

        VBox form = new VBox(8,
                new Label("用户名"), usernameField,
                new Label("姓名"), nameField,
                new Label("手机号"), mobileField,
                new Label("部门"), deptCombo,
                new Label("角色"), roleCombo,
                new Label("初始密码"), passwordField,
                enabledBox);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            if (creating && (usernameField.getText().isBlank() || passwordField.getText().length() < 6
                    || roleCombo.getValue() == null)) {
                Toast.show(null, "用户名、角色与初始密码（≥6 位）为必填", false);
                return null;
            }
            if (roleCombo.getValue() == null) {
                Toast.show(null, "请选择角色", false);
                return null;
            }
            String selectedDept = deptCombo.getValue();
            String deptId = null;
            if (selectedDept != null && !selectedDept.startsWith("（")) {
                for (DepartmentRow d : departmentsCache) {
                    if (d.name().equals(selectedDept)) {
                        deptId = d.id();
                        break;
                    }
                }
            }
            return new SavedUser(usernameField.getText().trim(), nameField.getText().trim(),
                    mobileField.getText().trim(), deptId, roleCombo.getValue(),
                    creating ? passwordField.getText() : null, enabledBox.isSelected());
        });
        ThemeManager.attachDialog(dialog);

        dialog.showAndWait().ifPresent(saved -> async(() -> {
            if (creating) {
                admin.createUser(new EnterpriseAdminAccess.UserDraft(saved.username(), saved.name(),
                        saved.mobile(), saved.departmentId(), saved.role(), saved.password()));
            } else {
                admin.updateUser(row.id(), new EnterpriseAdminAccess.UserEdit(saved.name(),
                        saved.mobile(), saved.departmentId(), saved.role(), saved.enabled()));
            }
            return null;
        }, unused -> {
            Toast.show(null, creating ? "用户已创建" : "用户已更新", true);
            loadUsers(userPage);
        }));
    }

    private void editUser(UserRow row) {
        userDialog(row);
    }

    private void deleteUser(UserRow row) {
        if (!confirm("删除用户「" + (row.name() == null || row.name().isBlank() ? row.username() : row.name())
                + "」？内置超管与自己不可删除。")) {
            return;
        }
        async(() -> {
            admin.deleteUser(row.id());
            return null;
        }, unused -> {
            Toast.show(null, "用户已删除", true);
            loadUsers(userPage);
        });
    }

    // ---------------- 部门 Tab ----------------

    private VBox buildDepartmentsPane() {
        Button refreshButton = new Button("刷新");
        Button newButton = new Button("＋ 新建部门");
        newButton.getStyleClass().add("primary");
        deptTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        deptTable.setPlaceholder(new Label("暂无部门"));
        deptTable.getColumns().addAll(
                col("部门名称", 260, DepartmentRow::name),
                col("成员数", 100, DepartmentRow::memberCount),
                actionColumn("操作", 200, row -> {
                    Button rename = new Button("重命名");
                    rename.setOnAction(e -> renameDepartment(row));
                    Button del = new Button("删除");
                    del.getStyleClass().add("danger");
                    del.setOnAction(e -> deleteDepartment(row));
                    return new HBox(6, rename, del);
                }));
        HBox toolbar = new HBox(8, refreshButton, newButton);
        refreshButton.setOnAction(e -> loadDepartments());
        newButton.setOnAction(e -> promptText("新建部门", "部门名称", "")
                .ifPresent(name -> async(() -> {
                    admin.createDepartment(name);
                    return null;
                }, unused -> {
                    Toast.show(null, "部门已创建", true);
                    loadDepartments();
                })));
        VBox pane = new VBox(8, toolbar, deptTable);
        VBox.setVgrow(deptTable, Priority.ALWAYS);
        return pane;
    }

    private void loadDepartments() {
        async(() -> admin.departments(null), rows -> {
            departmentsCache = new ArrayList<>(rows);
            deptTable.getItems().setAll(rows);
        });
    }

    private void renameDepartment(DepartmentRow row) {
        promptText("重命名部门", "部门名称", row.name()).ifPresent(name -> async(() -> {
            admin.renameDepartment(row.id(), name);
            return null;
        }, unused -> {
            Toast.show(null, "部门已重命名", true);
            loadDepartments();
        }));
    }

    private void deleteDepartment(DepartmentRow row) {
        if (!confirm("删除部门「" + row.name() + "」？仍有成员的部门会被服务端拒绝。")) {
            return;
        }
        async(() -> {
            admin.deleteDepartment(row.id());
            return null;
        }, unused -> {
            Toast.show(null, "部门已删除", true);
            loadDepartments();
        });
    }

    // ---------------- 角色 Tab ----------------

    private VBox buildRolesPane() {
        Button refreshButton = new Button("刷新");
        Button newButton = new Button("＋ 新建角色");
        newButton.getStyleClass().add("primary");
        roleTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        roleTable.setPlaceholder(new Label("暂无角色"));
        roleTable.getColumns().addAll(
                col("角色名称", 170, RoleRow::name),
                col("类型", 100, row -> row.builtIn() ? "内置只读" : "自定义"),
                col("权限", 300, row -> String.join("、", row.permissions())));
        TableColumn<RoleRow, Void> actions = new TableColumn<>("操作");
        actions.setPrefWidth(160);
        actions.setCellFactory(c -> new TableCell<>() {
            private final Button editButton = new Button("编辑");
            private final Button delButton = new Button("删除");
            private final HBox box = new HBox(6, editButton, delButton);

            {
                delButton.getStyleClass().add("danger");
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                RoleRow row = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || row == null || row.builtIn()) {
                    setGraphic(null);
                    return;
                }
                editButton.setOnAction(e -> roleDialog(row));
                delButton.setOnAction(e -> deleteRole(row));
                setGraphic(box);
            }
        });
        roleTable.getColumns().add(actions);

        HBox toolbar = new HBox(8, refreshButton, newButton);
        refreshButton.setOnAction(e -> loadRoles());
        newButton.setOnAction(e -> roleDialog(null));
        VBox pane = new VBox(8, toolbar, roleTable);
        VBox.setVgrow(roleTable, Priority.ALWAYS);
        return pane;
    }

    private void loadRoles() {
        async(() -> admin.roles(), rows -> {
            rolesCache = new ArrayList<>(rows);
            roleTable.getItems().setAll(rows);
            loadCapabilitiesQuietly();
        });
    }

    private void loadCapabilitiesQuietly() {
        async(() -> admin.capabilities(), caps -> capabilitiesCache = new ArrayList<>(caps), true);
    }

    private void roleDialog(RoleRow row) {
        boolean creating = row == null;
        Dialog<RoleDraft> dialog = new Dialog<>();
        dialog.setTitle(creating ? "新建角色" : "编辑角色「" + row.name() + "」");
        dialog.initOwner(stage);
        TextField nameField = new TextField(creating ? "" : row.name());
        nameField.setPromptText("角色名称");
        if (capabilitiesCache.isEmpty()) {
            loadCapabilitiesQuietly();
        }
        List<CheckBox> boxes = new ArrayList<>();
        for (CapabilityRow cap : capabilitiesCache) {
            CheckBox box = new CheckBox(cap.id() + "（" + cap.name() + "）");
            box.setUserData(cap.id());
            if (!creating && row.permissions() != null && row.permissions().contains(cap.id())) {
                box.setSelected(true);
            }
            boxes.add(box);
        }
        VBox capsBox = new VBox(6);
        boxes.forEach(cb -> capsBox.getChildren().add(cb));
        VBox form = new VBox(8, new Label("角色名称"), nameField, new Label("能力（v1 存档，鉴权后续版本接线）"), capsBox);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK || nameField.getText().isBlank()) {
                if (button == ButtonType.OK) {
                    Toast.show(null, "角色名称不能为空", false);
                }
                return null;
            }
            List<String> permissions = boxes.stream().filter(CheckBox::isSelected)
                    .map(cb -> (String) cb.getUserData()).toList();
            return new RoleDraft(nameField.getText().trim(), permissions);
        });
        ThemeManager.attachDialog(dialog);
        dialog.showAndWait().ifPresent(draft -> async(() -> {
            RoleDraft save = new RoleDraft(draft.name(), draft.permissions());
            if (creating) {
                admin.createRole(save);
            } else {
                admin.updateRole(row.id(), save);
            }
            return null;
        }, unused -> {
            Toast.show(null, "角色已保存", true);
            loadRoles();
        }));
    }

    private void deleteRole(RoleRow row) {
        if (!confirm("删除角色「" + row.name() + "」？仍有用户使用的角色会被服务端拒绝。")) {
            return;
        }
        async(() -> {
            admin.deleteRole(row.id());
            return null;
        }, unused -> {
            Toast.show(null, "角色已删除", true);
            loadRoles();
        });
    }

    // ---------------- 用量 / 配额 Tab（ENTERPRISE_METERING rev2：企业/部门/用户） ----------------

    private VBox buildQuotaPane() {
        Button refreshButton = new Button("刷新");
        Button saveButton = new Button("保存企业默认");
        saveButton.getStyleClass().add("primary");
        quotaTotalLabel.getStyleClass().add("status");
        quotaBudgetField.setPrefWidth(110);
        quotaBudgetField.setPromptText("不限=留空");
        quotaPerUserField.setPrefWidth(110);
        quotaPerUserField.setPromptText("不限=留空");

        quotaDeptTable.setPrefHeight(140);
        quotaDeptTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        quotaDeptTable.setPlaceholder(new Label("暂无部门"));
        quotaDeptTable.getColumns().setAll(
                col("部门", 180, EnterpriseAdminAccess.QuotaSummary.DepartmentUsage::departmentName),
                col("调用", 70, EnterpriseAdminAccess.QuotaSummary.DepartmentUsage::calls),
                col("用量 $", 100, u -> String.format("%.4f", u.costUsd())),
                col("限额 $", 90, u -> u.monthlyBudgetUsd() == null ? "不限"
                        : String.format("%.2f", u.monthlyBudgetUsd())),
                actionColumn("设置", 90, u -> {
                    Button b = new Button("设限额");
                    b.setOnAction(e -> setDeptLimit(u));
                    return new HBox(b);
                }));
        quotaUserTable.setPrefHeight(180);
        quotaUserTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        quotaUserTable.setPlaceholder(new Label("暂无用户"));
        quotaUserTable.getColumns().setAll(
                col("用户", 130, EnterpriseAdminAccess.QuotaSummary.UserUsage::username),
                col("部门", 110, EnterpriseAdminAccess.QuotaSummary.UserUsage::departmentName),
                col("调用", 60, EnterpriseAdminAccess.QuotaSummary.UserUsage::calls),
                col("用量 $", 100, u -> String.format("%.4f", u.costUsd())),
                col("限额 $", 90, u -> u.monthlyUsd() == null ? "不限"
                        : String.format("%.2f", u.monthlyUsd())),
                actionColumn("设置", 90, u -> {
                    Button b = new Button("设限额");
                    b.setOnAction(e -> setUserLimit(u));
                    return new HBox(b);
                }));
        quotaModelTable.setPrefHeight(120);
        quotaModelTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        quotaModelTable.setPlaceholder(new Label("本月暂无模型用量"));
        quotaModelTable.getColumns().setAll(
                col("模型", 150, EnterpriseAdminAccess.QuotaSummary.ModelUsage::modelAlias),
                col("调用", 60, EnterpriseAdminAccess.QuotaSummary.ModelUsage::calls),
                col("成本 $", 90, m -> String.format("%.4f", m.costUsd())),
                col("月限(调用/$)", 130, m -> {
                    var limit = modelLimitMap.get(m.modelAlias());
                    if (limit == null) {
                        return "不限";
                    }
                    return (limit.monthlyCalls() == null ? "-" : limit.monthlyCalls())
                            + " / " + (limit.monthlyUsd() == null ? "-"
                            : String.format("%.2f", limit.monthlyUsd()));
                }),
                actionColumn("设置", 80, m -> {
                    Button b = new Button("设月限");
                    b.setOnAction(e -> setModelLimit(m));
                    return new HBox(b);
                }));

        HBox totalRow = new HBox(8, refreshButton, quotaTotalLabel);
        totalRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(quotaTotalLabel, Priority.ALWAYS);
        HBox limitRow = new HBox(8, new Label("企业月度总预算 $"), quotaBudgetField,
                new Label("每用户默认 $"), quotaPerUserField, saveButton);
        limitRow.setAlignment(Pos.CENTER_LEFT);

        refreshButton.setOnAction(e -> loadQuota());
        saveButton.setOnAction(e -> saveEnterpriseLimits());
        VBox pane = new VBox(8, totalRow,
                new Label("按部门（限额：部门总用量上限）"), quotaDeptTable,
                new Label("按用户（限额：个人上限，未设回企业每用户默认）"), quotaUserTable,
                new Label("按模型（参考用量）"), quotaModelTable,
                limitRow);
        pane.getChildren().get(3).getStyleClass().add("status");
        return pane;
    }

    private void loadQuota() {
        async(() -> {
            EnterpriseAdminAccess.QuotaSummary summary = admin.quotaSummary();
            EnterpriseAdminAccess.QuotaLimits limits = admin.quotaLimits();
            return new Object[]{summary, limits};
        }, result -> {
            EnterpriseAdminAccess.QuotaSummary summary =
                    (EnterpriseAdminAccess.QuotaSummary) result[0];
            EnterpriseAdminAccess.QuotaLimits limits =
                    (EnterpriseAdminAccess.QuotaLimits) result[1];
            quotaTotalLabel.setText("本月 " + summary.month() + " · 总调用 " + summary.totalCalls()
                    + " · 总成本 $" + String.format("%.4f", summary.totalCostUsd()));
            quotaDeptTable.getItems().setAll(summary.departments());
            quotaUserTable.getItems().setAll(summary.users());
            quotaModelTable.getItems().setAll(summary.models());
            quotaBudgetField.setText(limits.monthlyBudgetUsd() == null ? ""
                    : String.valueOf(limits.monthlyBudgetUsd()));
            quotaPerUserField.setText(limits.monthlyPerUserUsd() == null ? ""
                    : String.valueOf(limits.monthlyPerUserUsd()));
            modelLimitMap.clear();
            for (EnterpriseAdminAccess.QuotaLimits.ModelLimit model : limits.modelLimits()) {
                modelLimitMap.put(model.modelAlias(), model);
            }
        });
    }

    /** 模型级月限额编辑（两项均留空并保存 = 清除该模型限额） */
    private void setModelLimit(EnterpriseAdminAccess.QuotaSummary.ModelUsage row) {
        EnterpriseAdminAccess.QuotaLimits.ModelLimit current = modelLimitMap.get(row.modelAlias());
        javafx.scene.control.TextField callsField = new javafx.scene.control.TextField();
        javafx.scene.control.TextField usdField = new javafx.scene.control.TextField();
        callsField.setPromptText("月调用上限（留空 = 不限/清除）");
        usdField.setPromptText("月成本上限 $（留空 = 不限/清除）");
        if (current != null) {
            callsField.setText(current.monthlyCalls() == null ? ""
                    : String.valueOf(current.monthlyCalls()));
            usdField.setText(current.monthlyUsd() == null ? ""
                    : String.valueOf(current.monthlyUsd()));
        }
        javafx.scene.control.Dialog<Boolean> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("模型月限额：" + row.modelAlias());
        dialog.initOwner(stage);
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("模型「" + row.modelAlias() + "」企业级月限额（C2）"),
                callsField, usdField,
                new Label("两项都留空并保存 = 清除该模型限额（恢复不限）")));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.setResultConverter(button -> button == ButtonType.OK ? Boolean.TRUE : null);
        ThemeManager.attachDialog(dialog);
        dialog.showAndWait().ifPresent(ok -> {
            Long calls = parseLongOrNull(callsField.getText());
            Double usd = parseQuotaValue(usdField.getText());
            if (calls != null && calls <= 0) {
                Toast.show(null, "调用上限需为正整数", false);
                return;
            }
            if (usd != null && usd <= 0) {
                Toast.show(null, "成本上限需为正数", false);
                return;
            }
            async(() -> admin.setModelLimit(row.modelAlias(), calls, usd), unused -> {
                Toast.show(null, "模型限额已保存", true);
                loadQuota();
            });
        });
    }

    private static Long parseLongOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private void saveEnterpriseLimits() {
        Double budget = parseQuotaValue(quotaBudgetField.getText());
        Double perUser = parseQuotaValue(quotaPerUserField.getText());
        if (budget != null && budget == Double.NaN) {
            Toast.show(null, "企业总预算格式错误", false);
            return;
        }
        if (perUser != null && perUser == Double.NaN) {
            Toast.show(null, "每用户默认预算格式错误", false);
            return;
        }
        async(() -> admin.updateQuotaLimits(budget, perUser), unused -> {
            Toast.show(null, "企业默认限额已保存", true);
            loadQuota();
        });
    }

    private void setDeptLimit(EnterpriseAdminAccess.QuotaSummary.DepartmentUsage row) {
        promptQuota("部门「" + row.departmentName() + "」月度限额", row.monthlyBudgetUsd())
                .ifPresent(value -> async(() -> admin.setDepartmentLimit(row.departmentId(),
                                value == -1.0 ? null : value),
                        unused -> {
                            Toast.show(null, "部门限额已保存", true);
                            loadQuota();
                        }));
    }

    private void setUserLimit(EnterpriseAdminAccess.QuotaSummary.UserUsage row) {
        promptQuota("用户「" + row.username() + "」月度限额", row.monthlyUsd())
                .ifPresent(value -> async(() -> admin.setUserLimit(row.username(),
                                value == -1.0 ? null : value),
                        unused -> {
                            Toast.show(null, "用户限额已保存", true);
                            loadQuota();
                        }));
    }

    /** 限额输入对话框：返回金额；留空 = null（清除/不限）；取消/格式错 = Optional.empty */
    private java.util.Optional<Double> promptQuota(String title, Double current) {
        javafx.scene.control.TextField field = new javafx.scene.control.TextField(
                current == null ? "" : String.valueOf(current));
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(title);
        dialog.initOwner(stage);
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("月度限额 $（留空 = 清除/不限）"), field));
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == javafx.scene.control.ButtonType.OK
                ? field.getText().trim() : null);
        com.omniforge.ui.theme.ThemeManager.attachDialog(dialog);
        String value = dialog.showAndWait().orElse(null);
        if (value == null) {
            return java.util.Optional.empty();
        }
        if (value.isEmpty()) {
            return java.util.Optional.of(-1.0); // 清除（=null 限额）
        }
        try {
            double parsed = Double.parseDouble(value);
            return parsed < 0 ? java.util.Optional.empty()
                    : java.util.Optional.of(parsed);
        } catch (NumberFormatException e) {
            Toast.show(null, "金额格式错误", false);
            return java.util.Optional.empty();
        }
    }

    /** 空/空白 → null；非法数值 → NaN（调用方判错提示） */
    private static Double parseQuotaValue(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return value < 0 ? Double.NaN : value;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ---------------- 共享 ----------------

    private void refreshAll() {
        async(() -> admin.departments(null), rows -> {
            departmentsCache = new ArrayList<>(rows);
            deptTable.getItems().setAll(rows);
            loadRolesAndCaps();
        });
    }

    private void loadRolesAndCaps() {
        async(() -> {
            List<RoleRow> roles = admin.roles();
            List<CapabilityRow> caps = admin.capabilities();
            return new Object[]{roles, caps};
        }, result -> {
            @SuppressWarnings("unchecked")
            List<RoleRow> roles = (List<RoleRow>) result[0];
            @SuppressWarnings("unchecked")
            List<CapabilityRow> caps = (List<CapabilityRow>) result[1];
            rolesCache = new ArrayList<>(roles);
            capabilitiesCache = new ArrayList<>(caps);
            roleTable.getItems().setAll(roles);
            loadUsers(0);
            loadQuota();
        });
    }

    private <T> void async(IoAction<T> io, java.util.function.Consumer<T> onSuccess, boolean quiet) {
        async(io, onSuccess);
    }

    private record SavedUser(String username, String name, String mobile, String departmentId,
                             String role, String password, Boolean enabled) {
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int indexOfName(ComboBox<String> combo, String name) {
        for (int i = 0; i < combo.getItems().size(); i++) {
            if (combo.getItems().get(i).equals(name)) {
                return i;
            }
        }
        return 0;
    }

    /** 数据列（record 访问器 → 文本） */
    private static <T> TableColumn<T, Object> col(String title, double width,
                                                  Function<T, Object> extractor) {
        TableColumn<T, Object> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(extractor.apply(cd.getValue())));
        return column;
    }

    /** 操作按钮列（每行一组按钮） */
    private static <T> TableColumn<T, Void> actionColumn(String title, double width,
                                                         Function<T, HBox> buttons) {
        TableColumn<T, Void> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                T row = getTableRow() == null ? null : getTableRow().getItem();
                setGraphic(empty || row == null ? null : buttons.apply(row));
            }
        });
        return column;
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("确认");
        alert.initOwner(stage);
        ThemeManager.attachDialog(alert);
        return alert.showAndWait().map(ButtonType.OK::equals).orElse(false);
    }

    private Optional<String> promptText(String title, String fieldLabel, String initial) {
        TextField field = new TextField(initial == null ? "" : initial);
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.initOwner(stage);
        dialog.getDialogPane().setContent(new VBox(8, new Label(fieldLabel), field));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK && !field.getText().isBlank()
                ? field.getText().trim() : null);
        ThemeManager.attachDialog(dialog);
        return dialog.showAndWait();
    }
}
