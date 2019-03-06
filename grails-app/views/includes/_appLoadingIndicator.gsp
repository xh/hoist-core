%{--
  - This file belongs to Hoist, an application development toolkit
  - developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
  -
  - Copyright © 2019 Extremely Heavy Industries Inc.
  --}%

%{-- CSS only loading indicator --}%
<style type="text/css">
    html,
    body {
        margin: 0;
        padding: 0;
    }

    #appLoadingContainer {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgb(<%= loadingBgRGB.join(', ') %>);
    }

    #appLoadingIndicator,
    #appLoadingIndicator:after {
        margin: -50px 0 0 -50px;
        border-radius: 50%;
        width: 100px;
        height: 100px;
    }

    #appLoadingIndicator {
        position: absolute;
        top: 50%;
        left: 50%;
        font-size: 10px;
        text-indent: -9999em;
        border-top: 5px solid rgba(<%= loadingFgRGB.join(', ') %>, 0.4);
        border-right: 5px solid rgba(<%= loadingFgRGB.join(', ') %>, 0.4);
        border-bottom: 5px solid rgba(<%= loadingFgRGB.join(', ') %>, 0.4);
        border-left: 5px solid rgba(<%= loadingFgRGB.join(', ') %>, 1);
        -webkit-transform: translateZ(0);
        -ms-transform: translateZ(0);
        transform: translateZ(0);
        -webkit-animation: appLoadingAnim 0.5s infinite linear;
        animation: appLoadingAnim 0.5s infinite linear;
    }

    @-webkit-keyframes appLoadingAnim {
        0% {
            -webkit-transform: rotate(0deg);
            transform: rotate(0deg);
        }
        100% {
            -webkit-transform: rotate(360deg);
            transform: rotate(360deg);
        }
    }

    @keyframes appLoadingAnim {
        0% {
            -webkit-transform: rotate(0deg);
            transform: rotate(0deg);
        }
        100% {
            -webkit-transform: rotate(360deg);
            transform: rotate(360deg);
        }
    }

</style>
<div id="appLoadingContainer">
    <div id="appLoadingIndicator"></div>
</div>