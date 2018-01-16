%{--
  - This file belongs to Hoist, an application development toolkit
  - developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
  -
  - Copyright © 2018 Extremely Heavy Industries Inc.
  --}%

<g:render template="/includes/header"/>

<body>
    <div class='xh-static-shell'>
        <div class='xh-static-header'>Unsupported Web Browser</div>
        <div class="xh-static-shell-inner">
            Your web browser is not currently supported by this application - please try another if available.<br><br>
            Your browser was identified as:
            <table class="xh-detected-browser-table">
                <tr><th>Browser:</th><td>${detectedBrowser}</td></tr>
                <tr><th>Version:</th><td>${detectedVersion}</td></tr>
                <tr><th>Device:</th><td>${detectedDevice}</td></tr>
                <tr><th>User Agent:</th><td>${userAgent}</td></tr>
            </table>
        </div>
    </div>
</body>
</html>
