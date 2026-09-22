Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$script:remaining = 10
$script:hibernate = $false

$form = New-Object System.Windows.Forms.Form
$form.Text = "Hibernate"
$form.Size = New-Object System.Drawing.Size(380, 190)
$form.StartPosition = "CenterScreen"
$form.TopMost = $true
$form.FormBorderStyle = "FixedDialog"
$form.MaximizeBox = $false
$form.MinimizeBox = $false

$label = New-Object System.Windows.Forms.Label
$label.AutoSize = $false
$label.Size = New-Object System.Drawing.Size(340, 60)
$label.Location = New-Object System.Drawing.Point(16, 16)
$label.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$label.Font = New-Object System.Drawing.Font("Segoe UI", 18)
$label.Text = "Hibernate in 10"

$cancel = New-Object System.Windows.Forms.Button
$cancel.Text = "Cancel"
$cancel.Size = New-Object System.Drawing.Size(140, 40)
$cancel.Location = New-Object System.Drawing.Point(112, 90)
$cancel.Font = New-Object System.Drawing.Font("Segoe UI", 12)
$cancel.Add_Click({
    $script:remaining = -1
    $form.Close()
})

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 1000
$timer.Add_Tick({
    $script:remaining--
    if ($script:remaining -le 0) {
        $timer.Stop()
        $script:hibernate = $true
        $form.Close()
    } else {
        $label.Text = "Hibernate in $($script:remaining)"
    }
})

$form.Controls.Add($label)
$form.Controls.Add($cancel)
$form.Add_Shown({ $timer.Start() })
$form.Add_FormClosing({ $timer.Stop() })

[void]$form.ShowDialog()
$timer.Dispose()
$form.Dispose()

if ($script:hibernate) {
    Start-Process -FilePath "shutdown.exe" -ArgumentList "/h" -WindowStyle Hidden
}
