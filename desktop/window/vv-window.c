/*
 * Окно интерфейса для снапа.
 *
 * Интерфейс программы — страница, которую она отдаёт сама себе на
 * 127.0.0.1. Вне снапа её рисует браузер человека в режиме приложения
 * (chrome --app), но из строгого снапа браузеров хозяина не видно вовсе.
 * Поэтому снап везёт своё окно: WebKitGTK из gnome-42-2204, тот же
 * движок, что у GNOME Web, — и окно выглядит как обычная программа, без
 * адресной строки и вкладок.
 *
 *   vv-window http://127.0.0.1:48713/
 *
 * Своё окно умеет ровно одно: показать эту страницу. Любая ссылка
 * наружу — сайт, Telegram, исходный код — уходит браузеру человека
 * (через портал рабочего стола), а не открывается здесь же: окно не
 * браузер, и вести себя как браузер ему незачем.
 *
 * Собирается под Ubuntu 22.04 (см. build.sh рядом): на ней основан
 * core22, и glibc у них одна.
 */
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>
#include <string.h>

static const char *home_url;

/* Своё — только адреса самой программы. */
static gboolean is_own(const char *uri) {
    return uri && g_str_has_prefix(uri, home_url);
}

static void open_outside(GtkWidget *window, const char *uri) {
    GError *error = NULL;
    if (!gtk_show_uri_on_window(GTK_WINDOW(window), uri, GDK_CURRENT_TIME, &error)) {
        g_warning("не открылась ссылка %s: %s", uri, error->message);
        g_error_free(error);
    }
}

static gboolean on_decide_policy(WebKitWebView *view, WebKitPolicyDecision *decision,
                                 WebKitPolicyDecisionType type, gpointer window) {
    if (type != WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION &&
        type != WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
        return FALSE;
    }
    WebKitNavigationAction *action = webkit_navigation_policy_decision_get_navigation_action(
        WEBKIT_NAVIGATION_POLICY_DECISION(decision));
    const char *uri = webkit_uri_request_get_uri(webkit_navigation_action_get_request(action));

    /* target=_blank и всё, что ведёт не к нам, — браузеру человека. */
    if (type == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION || !is_own(uri)) {
        open_outside(GTK_WIDGET(window), uri);
        webkit_policy_decision_ignore(decision);
        return TRUE;
    }
    return FALSE;
}

/* Заголовок окна — заголовок страницы: его задаёт сама программа. */
static void on_title(WebKitWebView *view, GParamSpec *spec, gpointer window) {
    const char *title = webkit_web_view_get_title(view);
    if (title && *title) {
        gtk_window_set_title(GTK_WINDOW(window), title);
    }
}

/* Программа ещё поднимается или уже выключилась — страница не открылась.
 * Пробуем снова через секунду, а не показываем человеку ошибку WebKit. */
static gboolean retry(gpointer view) {
    webkit_web_view_load_uri(WEBKIT_WEB_VIEW(view), home_url);
    return G_SOURCE_REMOVE;
}

static gboolean on_load_failed(WebKitWebView *view, WebKitLoadEvent event, char *uri,
                               GError *error, gpointer data) {
    if (g_error_matches(error, WEBKIT_NETWORK_ERROR, WEBKIT_NETWORK_ERROR_CANCELLED)) {
        return FALSE;
    }
    /* В stdout: вне разбора он никуда не ведёт, а при разборе без этой
     * строки белое окно ничего не объясняет. */
    g_print("vv-window: не открылось %s: %s\n", uri, error->message);
    g_timeout_add_seconds(1, retry, view);
    return TRUE;
}

int main(int argc, char **argv) {
    /* По этому имени рабочий стол связывает окно со значком программы —
     * то же, что --class у chrome вне снапа. */
    g_set_prgname("vv");
    gtk_init(&argc, &argv);

    home_url = argc > 1 ? argv[1] : "http://127.0.0.1:48713/";

    GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(window), "VibeVPN");
    gtk_window_set_default_size(GTK_WINDOW(window), 1060, 720);
    gtk_window_set_icon_name(GTK_WINDOW(window), "vv");

    /* Без прокси: окно ходит только к самой программе на 127.0.0.1.
     * Иначе WebKit сперва спрашивает портал рабочего стола, через какой
     * прокси идти, а из снапа портал отвечает «not available inside the
     * sandbox» — и не открывается даже петля: окно остаётся белым. */
    WebKitWebsiteDataManager *data = webkit_web_context_get_website_data_manager(
        webkit_web_context_get_default());
    webkit_website_data_manager_set_network_proxy_settings(
        data, WEBKIT_NETWORK_PROXY_MODE_NO_PROXY, NULL);

    GtkWidget *view = webkit_web_view_new();
    WebKitSettings *settings = webkit_web_view_get_settings(WEBKIT_WEB_VIEW(view));
    /* Не браузер: ни инструментов разработчика, ни масштаба по Ctrl+колесо. */
    webkit_settings_set_enable_developer_extras(settings, FALSE);
    webkit_settings_set_zoom_text_only(settings, FALSE);
    /* Ошибки JavaScript страницы — в stdout: без этого пустое окно ничего
     * не объясняет. */
    webkit_settings_set_enable_write_console_messages_to_stdout(settings, TRUE);

    g_signal_connect(view, "decide-policy", G_CALLBACK(on_decide_policy), window);
    g_signal_connect(view, "notify::title", G_CALLBACK(on_title), window);
    g_signal_connect(view, "load-failed", G_CALLBACK(on_load_failed), NULL);
    g_signal_connect(window, "destroy", G_CALLBACK(gtk_main_quit), NULL);

    gtk_container_add(GTK_CONTAINER(window), view);
    webkit_web_view_load_uri(WEBKIT_WEB_VIEW(view), home_url);
    gtk_widget_show_all(window);

    gtk_main();
    return 0;
}
