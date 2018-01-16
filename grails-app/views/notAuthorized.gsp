%{--
  - This file belongs to Hoist, an application development toolkit
  - developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
  -
  - Copyright © 2018 Extremely Heavy Industries Inc.
  --}%

<g:render template="/includes/header"/>

<body>
    <div class='xh-static-shell'>
        <div class='xh-static-header'>Access Denied</div>
        <div class="xh-static-shell-inner">
            <p>${exception.message}</p>
        </div>
    </div>
</body>
</html>
