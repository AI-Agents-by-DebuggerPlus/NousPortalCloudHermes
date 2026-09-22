using System.Windows;
using AhccDesktopViewer.Services;

namespace AhccDesktopViewer;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        AppLogger.Info("AHCCDV starting");
        DispatcherUnhandledException += (_, args) =>
        {
            AppLogger.Error("UI exception", args.Exception);
            args.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            if (args.ExceptionObject is Exception ex)
                AppLogger.Error("Unhandled", ex);
        };
    }

    protected override void OnExit(ExitEventArgs e)
    {
        AppLogger.Info("AHCCDV exiting");
        base.OnExit(e);
    }
}
