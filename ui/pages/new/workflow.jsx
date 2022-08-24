import Alert from "../../components/Alert"
import DetailPage from "../../components/layouts/DetailPage"
import classNames from "classnames"
import fetcher from "../../components/lib/json-fetcher"
import submissionToSource from "../../components/lib/submission-source"
import SettingsContext from "../../components/lib/SettingsContext"
import Editor, { useMonaco } from "@monaco-editor/react"
import Color from "color"
import { useCallback, useContext, useEffect, useRef, useState } from "react"
import { Circle } from "lucide-react"
import { useRouter } from "next/router"
import styles from "./workflow.scss"

const Submit = () => {
  const settings = useContext(SettingsContext.State)

  const [ready, setReady] = useState(false)
  const [value, setValue] = useState()
  const [error, setError] = useState()
  const [refreshThemeCounter, setRefreshThemeCounter] = useState(0)
  const router = useRouter()

  const editorRef = useRef()
  const themeDefaultRef = useRef()
  const themeDarkRef = useRef()
  const monaco = useMonaco()

  function getThemeColor(rootStyle, name) {
    return Color(rootStyle.getPropertyValue(name)).hex()
  }

  const defineTheme = useCallback((name, backgroundColor, foregroundColor,
      lineHighlightBorderColor, editorLineNumberColor) => {
    monaco.editor.defineTheme(name, {
      base: "vs-dark",
      inherit: true,
      rules: [
        { token: "comment", foreground: "7f9f7f" },
        { token: "keyword", foreground: "e3ceab" },
        { token: "number", foreground: "8cd0d3" },
        { token: "type", foreground: "ffffff" },
        { token: "operators", foreground: "ffffff" },
        { token: "string", foreground: "cc9393" }
      ],
      colors: {
        "editor.background": backgroundColor,
        "editor.foreground": foregroundColor,
        "editor.lineHighlightBorder": lineHighlightBorderColor,
        "editorLineNumber.foreground": editorLineNumberColor,
        "editor.selectionBackground": "#6590cc",
        "editor.inactiveSelectionBackground": "#5580bb",
        "scrollbar.shadow": backgroundColor
      }
    })
  }, [monaco])

  useEffect(() => {
    if (monaco) {
      let defaultStyle = window.getComputedStyle(themeDefaultRef.current)
      let darkStyle = window.getComputedStyle(themeDarkRef.current)

      defineTheme("steep-default",
        getThemeColor(defaultStyle, "--code-bg"),
        getThemeColor(defaultStyle, "--code-fg"),
        getThemeColor(defaultStyle, "--gray-700"),
        getThemeColor(defaultStyle, "--gray-600")
      )

      defineTheme("steep-dark",
        getThemeColor(darkStyle, "--code-bg"),
        getThemeColor(darkStyle, "--code-fg"),
        getThemeColor(darkStyle, "--gray-700"),
        getThemeColor(darkStyle, "--gray-200")
      )

      // force call to monaco.editor.setTheme after all themes have been defined
      setRefreshThemeCounter(c => c + 1)
    }
  }, [monaco, defineTheme])

  useEffect(() => {
    if (monaco) {
      monaco.editor.setTheme(`steep-${settings.theme}`)
    }
  }, [monaco, settings.theme, refreshThemeCounter])

  useEffect(() => {
    if (router.query.from !== undefined) {
      fetcher(`${process.env.baseUrl}/workflows/${router.query.from}`).then(response => {
        setValue(submissionToSource(response).yaml)
        setReady(true)
        if (editorRef.current && monaco) {
          editorRef.current.setSelection(new monaco.Selection(0, 0, 0, 0))
        }
      }).catch(error => {
        setError(error.message)
        console.log(error)
      })
    }
  }, [router.query.from, monaco])

  function handleEditorOnMount(editor) {
    editorRef.current = editor
    editor.focus()
  }

  function handleEditorChange(value) {
    setValue(value)
    setReady(value !== undefined && value.length > 0)
  }

  function onSubmit() {
    if (value === undefined || value.length === 0) {
      return
    }

    setReady(false)

    fetcher(`${process.env.baseUrl}/workflows/`, false, {
      method: "POST",
      body: value
    }).then(response => {
      router.push("/workflows/[id]", `/workflows/${response.id}`)
    }).catch(error => {
      setError(error.message)
      console.log(error)
      setReady(true)
    })
  }

  function onCancel() {
    router.back()
  }

  const loading = (<>
    <div className="loading"><Circle /></div>
    <style jsx>{styles}</style>
  </>)

  const options = {
    // see main.scss
    fontFamily: "SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
    fontSize: "16.74px",
    lineHeight: "28.458px",

    automaticLayout: true,
    lineNumbers: "on",
    minimap: {
      enabled: false
    },
    renderLineHighlight: false,
    scrollBeyondLastLine: false
  }

  return (
    <DetailPage title="New workflow" footerNoTopMargin={true}>
      <div data-theme="default" ref={themeDefaultRef}></div>
      <div data-theme="dark" ref={themeDarkRef}></div>
      <div className={classNames("editor-container", { "with-error": error !== undefined })}>
        {error && <div>
          <Alert error>{error}</Alert>
        </div>}
        <div className="editor-main">
          <div className="editor-wrapper">
            <Editor width="100%" height="100%" language="yaml" value={value}
              onMount={handleEditorOnMount} onChange={handleEditorChange}
              theme="steep" loading={loading} options={options} />
          </div>
          <div className="buttons">
            <button className="btn primary submit-button" disabled={!ready}
              onClick={onSubmit}>Submit</button>
            <button className="btn cancel-button" onClick={onCancel}>Cancel</button>
          </div>
        </div>
      </div>
      <style jsx>{styles}</style>
    </DetailPage>
  )
}

export default Submit
